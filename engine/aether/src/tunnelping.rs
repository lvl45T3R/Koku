use std::net::{Ipv4Addr, Ipv6Addr, SocketAddr};
use std::time::{Duration, Instant};

use tokio::sync::oneshot;

use crate::aethernoize::AetherNoizeConfig;
use crate::error::{AetherError, Result};
use crate::masque_h2;
use crate::netstack;
use crate::noize::NoizeConfig;
use crate::quic;
use crate::socks;
use crate::wireguard;

const PING_MTU: usize = 1280;
const HTTP_PROBE_HOST: &str = "www.gstatic.com";
const HTTP_PROBE_PATH: &str = "/generate_204";
const EXIT_PROBE_HOST: &str = "www.cloudflare.com";
const EXIT_PROBE_PATH: &str = "/cdn-cgi/trace";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExitRoute {
    pub ip: String,
    pub country_code: String,
    pub colo: String,
    pub latency: Duration,
}

struct AbortGuard<T>(tokio::task::JoinHandle<T>);

impl<T> Drop for AbortGuard<T> {
    fn drop(&mut self) {
        self.0.abort();
    }
}

fn http_probe_port() -> u16 {
    std::env::var("AETHER_IRONCLAD_PORT")
        .ok()
        .and_then(|v| v.parse().ok())
        .unwrap_or(80)
}

async fn http_probe(stack: &netstack::StackHandle) -> Result<()> {
    let ip = socks::dns_resolve(stack, HTTP_PROBE_HOST).await?;
    let dst = SocketAddr::new(ip, http_probe_port());

    let conn = stack.open_tcp(dst).await?;
    let (sender, mut from_stack) = conn.into_split();

    let request = format!(
        "GET {HTTP_PROBE_PATH} HTTP/1.1\r\nHost: {HTTP_PROBE_HOST}\r\nConnection: close\r\nUser-Agent: aether-ironclad\r\n\r\n"
    );
    sender.send(request.into_bytes()).await?;

    let deadline = tokio::time::Instant::now() + Duration::from_secs(6);
    let mut buf = Vec::new();
    let mut timed_out = false;

    loop {
        match tokio::time::timeout_at(deadline, from_stack.recv()).await {
            Ok(Some(chunk)) => {
                buf.extend_from_slice(&chunk);
                if buf.windows(2).any(|w| w == b"\r\n") || buf.len() >= 128 {
                    break;
                }
            }
            Ok(None) => break,
            Err(_) => {
                timed_out = true;
                break;
            }
        }
    }

    sender.close().await;

    if timed_out {
        return Err(AetherError::Other("http probe response timeout".into()));
    }

    let response = String::from_utf8_lossy(&buf);
    let status_line = response.lines().next().unwrap_or("").trim();

    if http_status_code(status_line) == Some(204) {
        Ok(())
    } else {
        Err(AetherError::Other(format!(
            "unexpected http probe response: {status_line}"
        )))
    }
}

fn http_status_code(status_line: &str) -> Option<u16> {
    let mut parts = status_line.split(' ');
    let version = parts.next()?;
    if !version.starts_with("HTTP/") {
        return None;
    }
    parts.next()?.parse().ok()
}

async fn exit_probe(stack: &netstack::StackHandle) -> Result<ExitRoute> {
    let ip = socks::dns_resolve(stack, EXIT_PROBE_HOST).await?;
    let dst = SocketAddr::new(ip, 80);
    let conn = stack.open_tcp(dst).await?;
    let (sender, mut from_stack) = conn.into_split();
    let request = format!(
        "GET {EXIT_PROBE_PATH} HTTP/1.1\r\nHost: {EXIT_PROBE_HOST}\r\nConnection: close\r\nUser-Agent: koku-exit-probe\r\nAccept: text/plain\r\n\r\n"
    );
    let started = Instant::now();
    sender.send(request.into_bytes()).await?;

    let deadline = tokio::time::Instant::now() + Duration::from_secs(8);
    let mut response = Vec::new();
    loop {
        match tokio::time::timeout_at(deadline, from_stack.recv()).await {
            Ok(Some(chunk)) => {
                response.extend_from_slice(&chunk);
                if response.len() > 16 * 1024 {
                    return Err(AetherError::Other(
                        "exit probe response is too large".into(),
                    ));
                }
            }
            Ok(None) => break,
            Err(_) => return Err(AetherError::Other("exit probe response timeout".into())),
        }
    }
    sender.close().await;

    parse_exit_trace(&String::from_utf8_lossy(&response), started.elapsed())
}

fn parse_exit_trace(response: &str, latency: Duration) -> Result<ExitRoute> {
    let (headers, body) = response
        .split_once("\r\n\r\n")
        .ok_or_else(|| AetherError::Other("exit probe returned an invalid HTTP response".into()))?;
    let status = headers.lines().next().unwrap_or("").trim();
    if http_status_code(status) != Some(200) {
        return Err(AetherError::Other(format!(
            "unexpected exit probe response: {status}"
        )));
    }

    let value = |key: &str| {
        body.lines().find_map(|line| {
            let (candidate, value) = line.trim().split_once('=')?;
            candidate
                .eq_ignore_ascii_case(key)
                .then(|| value.trim().to_string())
        })
    };
    let ip = value("ip")
        .filter(|value| !value.is_empty())
        .ok_or_else(|| AetherError::Other("exit probe did not return an IP address".into()))?;
    let country_code = value("loc")
        .map(|value| value.to_ascii_uppercase())
        .filter(|value| value.len() == 2)
        .ok_or_else(|| AetherError::Other("exit probe did not return a country code".into()))?;
    let colo = value("colo").unwrap_or_default();
    Ok(ExitRoute {
        ip,
        country_code,
        colo,
        latency,
    })
}

pub struct MasquePingParams {
    pub peer: SocketAddr,
    pub sni: String,
    pub authority: String,
    pub path: String,
    pub cert_pem: Vec<u8>,
    pub key_pem: Vec<u8>,
    pub noize: NoizeConfig,
    pub local_ipv4: Ipv4Addr,
    pub local_ipv4_str: String,
    pub local_ipv6_str: String,
}

pub async fn masque_http_ping(p: &MasquePingParams, timeout: Duration) -> Result<Duration> {
    let attempt = async {
        let (chans, internals) = quic::channels();
        let quic::Channels {
            outbound_tx,
            inbound_rx,
            ctrl_tx,
        } = chans;

        let stack = netstack::spawn(
            &p.local_ipv4_str,
            &p.local_ipv6_str,
            PING_MTU,
            inbound_rx,
            outbound_tx,
        )?;

        let (ready_tx, ready_rx) = oneshot::channel();

        let tunnel_task = if masque_h2::enabled() {
            let h2cfg = masque_h2::H2TunnelConfig {
                peer: masque_h2::h2_peer(p.peer),
                sni: p.sni.clone(),
                authority: p.authority.clone(),
                path: p.path.clone(),
                cert_pem: p.cert_pem.clone(),
                key_pem: p.key_pem.clone(),
                local_ipv4: p.local_ipv4,
                quiet: true,
                pin_endpoint: true,
                expected_pins: crate::consts::MASQUE_PINS
                    .iter()
                    .map(|p| p.to_vec())
                    .collect(),
            };
            AbortGuard(tokio::spawn(masque_h2::run(
                h2cfg,
                internals,
                None,
                Some(ready_tx),
            )))
        } else {
            let cfg = quic::TunnelConfig {
                peer: p.peer,
                sni: p.sni.clone(),
                authority: p.authority.clone(),
                path: p.path.clone(),
                cert_pem: p.cert_pem.clone(),
                key_pem: p.key_pem.clone(),
                ech_config_list: None,
                noize: p.noize.clone(),
                local_ipv4: p.local_ipv4,
                quiet: true,
            };
            AbortGuard(tokio::spawn(quic::run(
                cfg,
                internals,
                None,
                Some(ready_tx),
            )))
        };

        if ready_rx.await.is_err() {
            return Err(AetherError::Other(
                "tunnel exited before data-plane validation".into(),
            ));
        }

        let start = Instant::now();
        let result = http_probe(&stack).await.map(|()| start.elapsed());

        drop(ctrl_tx);
        drop(tunnel_task);
        result
    };

    match tokio::time::timeout(timeout, attempt).await {
        Ok(Ok(rtt)) => Ok(rtt),
        Ok(Err(e)) => Err(e),
        Err(_) => Err(AetherError::Other("ironclad http probe timeout".into())),
    }
}

pub async fn masque_exit_probe(p: &MasquePingParams, timeout: Duration) -> Result<ExitRoute> {
    let attempt = async {
        let (chans, internals) = quic::channels();
        let quic::Channels {
            outbound_tx,
            inbound_rx,
            ctrl_tx,
        } = chans;
        let stack = netstack::spawn(
            &p.local_ipv4_str,
            &p.local_ipv6_str,
            PING_MTU,
            inbound_rx,
            outbound_tx,
        )?;
        let (ready_tx, ready_rx) = oneshot::channel();
        let tunnel_task = if masque_h2::enabled() {
            let h2cfg = masque_h2::H2TunnelConfig {
                peer: masque_h2::h2_peer(p.peer),
                sni: p.sni.clone(),
                authority: p.authority.clone(),
                path: p.path.clone(),
                cert_pem: p.cert_pem.clone(),
                key_pem: p.key_pem.clone(),
                local_ipv4: p.local_ipv4,
                quiet: true,
                pin_endpoint: true,
                expected_pins: crate::consts::MASQUE_PINS
                    .iter()
                    .map(|pin| pin.to_vec())
                    .collect(),
            };
            AbortGuard(tokio::spawn(masque_h2::run(
                h2cfg,
                internals,
                None,
                Some(ready_tx),
            )))
        } else {
            let cfg = quic::TunnelConfig {
                peer: p.peer,
                sni: p.sni.clone(),
                authority: p.authority.clone(),
                path: p.path.clone(),
                cert_pem: p.cert_pem.clone(),
                key_pem: p.key_pem.clone(),
                ech_config_list: None,
                noize: p.noize.clone(),
                local_ipv4: p.local_ipv4,
                quiet: true,
            };
            AbortGuard(tokio::spawn(quic::run(
                cfg,
                internals,
                None,
                Some(ready_tx),
            )))
        };
        if ready_rx.await.is_err() {
            return Err(AetherError::Other(
                "tunnel exited before exit-route validation".into(),
            ));
        }
        let result = exit_probe(&stack).await;
        drop(ctrl_tx);
        drop(tunnel_task);
        result
    };

    match tokio::time::timeout(timeout, attempt).await {
        Ok(result) => result,
        Err(_) => Err(AetherError::Other("MASQUE exit probe timeout".into())),
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::{http_status_code, parse_exit_trace};

    #[test]
    fn reads_the_status_code_from_the_status_line() {
        assert_eq!(http_status_code("HTTP/1.1 204 No Content"), Some(204));
        assert_eq!(http_status_code("HTTP/1.1 200 OK"), Some(200));
        assert_eq!(http_status_code("HTTP/1.0 403 Forbidden"), Some(403));
    }

    #[test]
    fn a_header_that_merely_contains_204_is_not_a_success() {
        let response = "HTTP/1.1 200 OK\r\nContent-Length: 204\r\n\r\n";
        let status_line = response.lines().next().unwrap().trim();
        assert_ne!(http_status_code(status_line), Some(204));
    }

    #[test]
    fn rejects_lines_that_are_not_http_status_lines() {
        assert_eq!(http_status_code(""), None);
        assert_eq!(http_status_code("204"), None);
        assert_eq!(http_status_code("GET / HTTP/1.1"), None);
        assert_eq!(http_status_code("HTTP/1.1 abc"), None);
    }

    #[test]
    fn parses_cloudflare_exit_trace() {
        let response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nfl=1\nip=104.28.1.2\nloc=DE\ncolo=FRA\n";
        let exit = parse_exit_trace(response, Duration::from_millis(42)).unwrap();
        assert_eq!(exit.ip, "104.28.1.2");
        assert_eq!(exit.country_code, "DE");
        assert_eq!(exit.colo, "FRA");
        assert_eq!(exit.latency, Duration::from_millis(42));
    }

    #[test]
    fn rejects_exit_trace_without_country() {
        let response = "HTTP/1.1 200 OK\r\n\r\nip=104.28.1.2\n";
        assert!(parse_exit_trace(response, Duration::ZERO).is_err());
    }
}

pub struct WgPingParams {
    pub local_ipv4: Ipv4Addr,
    pub local_ipv6: Ipv6Addr,
    pub aethernoize: AetherNoizeConfig,
}

pub async fn wg_http_ping_established(
    session: wireguard::EstablishedSession,
    p: &WgPingParams,
    timeout: Duration,
) -> Result<Duration> {
    let attempt = async {
        let (outbound_tx, outbound_rx) =
            tokio::sync::mpsc::channel(crate::sysprofile::channel_capacity());
        let (inbound_tx, inbound_rx) =
            tokio::sync::mpsc::channel(crate::sysprofile::channel_capacity());

        let tunnel = wireguard::WgTunnel::from_established(
            session,
            std::sync::Arc::new(p.aethernoize.clone()),
            inbound_tx,
            p.local_ipv4,
        );

        let local_ipv4_str = p.local_ipv4.to_string();
        let local_ipv6_str = p.local_ipv6.to_string();
        let stack = netstack::spawn(
            &local_ipv4_str,
            &local_ipv6_str,
            PING_MTU,
            inbound_rx,
            outbound_tx,
        )?;

        let tunnel_task = AbortGuard(tokio::spawn(tunnel.run(outbound_rx)));

        let start = Instant::now();
        let result = http_probe(&stack).await.map(|()| start.elapsed());

        drop(tunnel_task);
        result
    };

    match tokio::time::timeout(timeout, attempt).await {
        Ok(Ok(rtt)) => Ok(rtt),
        Ok(Err(e)) => Err(e),
        Err(_) => Err(AetherError::Other("ironclad http probe timeout".into())),
    }
}

pub async fn wg_exit_probe_established(
    session: wireguard::EstablishedSession,
    p: &WgPingParams,
    timeout: Duration,
) -> Result<ExitRoute> {
    let attempt = async {
        let (outbound_tx, outbound_rx) =
            tokio::sync::mpsc::channel(crate::sysprofile::channel_capacity());
        let (inbound_tx, inbound_rx) =
            tokio::sync::mpsc::channel(crate::sysprofile::channel_capacity());
        let tunnel = wireguard::WgTunnel::from_established(
            session,
            std::sync::Arc::new(p.aethernoize.clone()),
            inbound_tx,
            p.local_ipv4,
        );
        let stack = netstack::spawn(
            &p.local_ipv4.to_string(),
            &p.local_ipv6.to_string(),
            PING_MTU,
            inbound_rx,
            outbound_tx,
        )?;
        let tunnel_task = AbortGuard(tokio::spawn(tunnel.run(outbound_rx)));
        let result = exit_probe(&stack).await;
        drop(tunnel_task);
        result
    };

    match tokio::time::timeout(timeout, attempt).await {
        Ok(result) => result,
        Err(_) => Err(AetherError::Other("WireGuard exit probe timeout".into())),
    }
}
