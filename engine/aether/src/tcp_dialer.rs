use std::net::{IpAddr, SocketAddr};
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;

use crate::error::{AetherError, Result};

const SOCKS5_PROXY_ENV: &str = "AETHER_MASQUE_H2_SOCKS5_PROXY";
const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);

pub fn socks5_proxy() -> Result<Option<SocketAddr>> {
    let Ok(value) = std::env::var(SOCKS5_PROXY_ENV) else {
        return Ok(None);
    };
    parse_socks5_proxy(&value)
}

fn parse_socks5_proxy(value: &str) -> Result<Option<SocketAddr>> {
    let value = value.trim();
    if value.is_empty() {
        return Ok(None);
    }

    let proxy = value
        .parse::<SocketAddr>()
        .map_err(|_| AetherError::Other(format!("invalid SOCKS5 proxy address: {value}")))?;
    if !proxy.ip().is_loopback() {
        return Err(AetherError::Other(
            "MASQUE2 only accepts a loopback StormDNS SOCKS5 proxy".into(),
        ));
    }
    Ok(Some(proxy))
}

pub async fn connect(peer: SocketAddr) -> Result<TcpStream> {
    let proxy = socks5_proxy()?;
    let target = proxy.unwrap_or(peer);
    let mut stream = tokio::time::timeout(CONNECT_TIMEOUT, TcpStream::connect(target))
        .await
        .map_err(|_| AetherError::Other(format!("TCP connect to {target} timed out")))?
        .map_err(AetherError::Io)?;
    let _ = stream.set_nodelay(true);

    if let Some(proxy) = proxy {
        log::debug!("[masque2] routing H2 TCP through StormDNS SOCKS5 at {proxy}");
        tokio::time::timeout(CONNECT_TIMEOUT, socks5_connect(&mut stream, peer))
            .await
            .map_err(|_| AetherError::Other("StormDNS SOCKS5 handshake timed out".into()))??;
    }

    Ok(stream)
}

async fn socks5_connect(stream: &mut TcpStream, peer: SocketAddr) -> Result<()> {
    stream.write_all(&[0x05, 0x01, 0x00]).await?;

    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await?;
    if greeting != [0x05, 0x00] {
        return Err(AetherError::Other(format!(
            "StormDNS SOCKS5 rejected unauthenticated negotiation: {greeting:02x?}"
        )));
    }

    let mut request = Vec::with_capacity(22);
    request.extend_from_slice(&[0x05, 0x01, 0x00]);
    match peer.ip() {
        IpAddr::V4(ip) => {
            request.push(0x01);
            request.extend_from_slice(&ip.octets());
        }
        IpAddr::V6(ip) => {
            request.push(0x04);
            request.extend_from_slice(&ip.octets());
        }
    }
    request.extend_from_slice(&peer.port().to_be_bytes());
    stream.write_all(&request).await?;

    let mut response = [0u8; 4];
    stream.read_exact(&mut response).await?;
    if response[0] != 0x05 {
        return Err(AetherError::Other(format!(
            "invalid StormDNS SOCKS5 response version {}",
            response[0]
        )));
    }
    if response[1] != 0x00 {
        return Err(AetherError::Other(format!(
            "StormDNS SOCKS5 connect failed with status 0x{:02x}",
            response[1]
        )));
    }

    let address_len = match response[3] {
        0x01 => 4,
        0x04 => 16,
        0x03 => {
            let mut len = [0u8; 1];
            stream.read_exact(&mut len).await?;
            len[0] as usize
        }
        value => {
            return Err(AetherError::Other(format!(
                "invalid StormDNS SOCKS5 address type 0x{value:02x}"
            )))
        }
    };
    let mut ignored = vec![0u8; address_len + 2];
    stream.read_exact(&mut ignored).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;

    #[tokio::test]
    async fn connects_to_an_ipv4_target_through_socks5() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy = listener.local_addr().unwrap();
        let target: SocketAddr = "162.159.197.3:443".parse().unwrap();

        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.unwrap();
            let mut greeting = [0u8; 3];
            stream.read_exact(&mut greeting).await.unwrap();
            assert_eq!(greeting, [0x05, 0x01, 0x00]);
            stream.write_all(&[0x05, 0x00]).await.unwrap();

            let mut request = [0u8; 10];
            stream.read_exact(&mut request).await.unwrap();
            assert_eq!(&request[..4], &[0x05, 0x01, 0x00, 0x01]);
            assert_eq!(&request[4..8], &[162, 159, 197, 3]);
            assert_eq!(u16::from_be_bytes([request[8], request[9]]), 443);
            stream
                .write_all(&[0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0])
                .await
                .unwrap();
        });

        let mut stream = TcpStream::connect(proxy).await.unwrap();
        socks5_connect(&mut stream, target).await.unwrap();
        server.await.unwrap();
    }

    #[test]
    fn rejects_non_loopback_proxy_configuration() {
        let result = parse_socks5_proxy("192.0.2.1:18000");
        assert!(result.is_err());
    }
}
