/// Count TCP data, excluding local SYN/ACK/RST and UDP DNS responses.
pub fn tcp_payload_len(packet: &[u8]) -> usize {
    let Some(first) = packet.first() else {
        return 0;
    };
    let (mut offset, end, mut next) = match first >> 4 {
        4 if packet.len() >= 20 => {
            let offset = (first & 15) as usize * 4;
            let end = u16::from_be_bytes([packet[2], packet[3]]) as usize;
            if offset < 20
                || end > packet.len()
                || end < offset
                || u16::from_be_bytes([packet[6], packet[7]]) & 0x1fff != 0
            {
                return 0;
            }
            (offset, end, packet[9])
        }
        6 if packet.len() >= 40 => {
            let end = 40 + u16::from_be_bytes([packet[4], packet[5]]) as usize;
            if end > packet.len() {
                return 0;
            }
            (40, end, packet[6])
        }
        _ => return 0,
    };
    for _ in 0..8 {
        if next == 6 {
            break;
        }
        if first >> 4 != 6 || offset + 2 > end {
            return 0;
        }
        let size = match next {
            0 | 43 | 60 => (packet[offset + 1] as usize + 1) * 8,
            51 => (packet[offset + 1] as usize + 2) * 4,
            44 if offset + 8 <= end => {
                if u16::from_be_bytes([packet[offset + 2], packet[offset + 3]]) & 0xfff8 != 0 {
                    return 0;
                }
                8
            }
            _ => return 0,
        };
        if offset + size > end {
            return 0;
        }
        next = packet[offset];
        offset += size;
    }
    if next != 6 || offset + 20 > end {
        return 0;
    }
    let header = (packet[offset + 12] >> 4) as usize * 4;
    if header < 20 || offset + header > end || packet[offset + 13] & 0x06 != 0 {
        return 0;
    }
    end - offset - header
}

#[cfg(test)]
mod tests {
    use super::*;
    fn ipv4(payload: usize) -> Vec<u8> {
        let mut p = vec![0; 40 + payload];
        p[0] = 0x45;
        p[2..4].copy_from_slice(&((40 + payload) as u16).to_be_bytes());
        p[9] = 6;
        p[32] = 0x50;
        p[33] = 0x18;
        p
    }
    #[test]
    fn acknowledgements_and_dns_do_not_confirm_remote_data() {
        assert_eq!(tcp_payload_len(&ipv4(0)), 0);
        let mut p = ipv4(30);
        p[9] = 17;
        assert_eq!(tcp_payload_len(&p), 0);
        p[9] = 6;
        p[33] = 0x04;
        assert_eq!(tcp_payload_len(&p), 0);
    }
    #[test]
    fn ipv4_tcp_data_and_truncation() {
        let p = ipv4(50);
        assert_eq!(tcp_payload_len(&p), 50);
        assert_eq!(tcp_payload_len(&p[..45]), 0);
        for n in 0..p.len() {
            let _ = tcp_payload_len(&p[..n]);
        }
    }
    #[test]
    fn ipv6_tcp_data_with_extension_header() {
        let mut p = vec![0; 40 + 8 + 20 + 30];
        p[0] = 0x60;
        p[5] = 58;
        p[6] = 0;
        p[40] = 6;
        p[60] = 0x50;
        p[61] = 0x18;
        assert_eq!(tcp_payload_len(&p), 30);
        p[40] = 17;
        assert_eq!(tcp_payload_len(&p), 0);
    }
}
