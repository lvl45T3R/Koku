pub const OPTIONS: &[&str] = &[
    "AETHER_HALF_CLOSE_SECS",
    "AETHER_IRONCLAD_PORT",
    "AETHER_MASQUE_H2_KEEPALIVE_SECS",
    "AETHER_MASQUE_H2_KEEPALIVE_TIMEOUT_SECS",
    "AETHER_MAX_CLIENTS",
    "AETHER_PSIPHON_READY_SECS",
    "AETHER_ROUTE_SNIFF",
    "AETHER_ROUTE_SNIFF_MS",
    "AETHER_TCP_CONNECT_SECS",
    "AETHER_TCP_KEEPALIVE_SECS",
    "AETHER_TOR_BRIDGE_SECS",
    "AETHER_TOR_CHECK",
    "AETHER_TOR_COUNTRY",
    "AETHER_TOR_DIRECT_SECS",
    "AETHER_TOR_LOG",
    "AETHER_TOR_STALL_SECS",
    "AETHER_WG_ENDPOINT_COOLDOWN_SECS",
    "AETHER_WG_STALE_SECS",
    "AETHER_ACCESS_CLIENT_ID",
    "AETHER_ACCESS_CLIENT_SECRET",
    "AETHER_ACCESS_TOKEN",
    "AETHER_API_FRAGMENT",
    "AETHER_DISABLE_GREASE",
    "AETHER_DNS",
    "AETHER_ECH",
    "AETHER_ECH_DNS",
    "AETHER_ECH_DOMAIN",
    "AETHER_ENROLL_ADDRESS",
    "AETHER_EXIT_LOC",
    "AETHER_EXIT_LOC_SECS",
    "AETHER_GATEWAY",
    "AETHER_GOOL_INNER",
    "AETHER_GOOL_MODE",
    "AETHER_IP",
    "AETHER_LOG_LEVEL",
    "AETHER_MASQUE_H2_FRAGMENT",
    "AETHER_MASQUE_H2_FRAGMENT_DELAY",
    "AETHER_MASQUE_H2_FRAGMENT_SIZE",
    "AETHER_MASQUE_H2_PEER",
    "AETHER_MASQUE_HTTP2",
    "AETHER_MASQUE_NO_DATA_CHECK",
    "AETHER_MASQUE_RECONNECT_SECS",
    "AETHER_MASQUE_STARTUP_SECS",
    "AETHER_MASQUE_VALIDATE_SECS",
    "AETHER_MIM_INNER_PEER",
    "AETHER_MIM_OUTER_PEER",
    "AETHER_MIM_PEERS",
    "AETHER_NOIZE",
    "AETHER_PEER",
    "AETHER_PERF_PROFILE",
    "AETHER_PSIPHON",
    "AETHER_PSIPHON_CDN_IPS",
    "AETHER_PSIPHON_CDN_SETS",
    "AETHER_PSIPHON_CDN_SNI",
    "AETHER_PSIPHON_CONFIG",
    "AETHER_PSIPHON_MODE",
    "AETHER_PSIPHON_REGION",
    "AETHER_PSIPHON_SERVER_ENTRIES",
    "AETHER_QUIC_V2",
    "AETHER_QUICK_RECONNECT",
    "AETHER_ROUTE_BLOCK",
    "AETHER_ROUTE_DIRECT",
    "AETHER_ROUTES_FILE",
    "AETHER_STATS",
    "AETHER_STATS_SECS",
    "AETHER_TEAM",
    "AETHER_TLS_CIPHERS",
    "AETHER_TLS_GROUPS",
    "AETHER_TLS_VERIFY",
    "AETHER_TOR",
    "AETHER_TOR_BRIDGE_FILE",
    "AETHER_TOR_BRIDGES",
    "AETHER_TOR_PT",
    "AETHER_TOR_PT_DIR",
    "AETHER_TOR_RELAY_PORTS",
    "AETHER_TOR_RELAYS",
    "AETHER_UPSTREAM",
    "AETHER_WG_KEEPALIVE",
    "AETHER_WG_NO_DATA_CHECK",
    "AETHER_WG_NO_PROFILE_RETRY",
    "AETHER_WG_PEER",
    "AETHER_WG_RECONNECT_SECS",
    "AETHER_WG_VALIDATE_SECS",
    "AETHER_WIW_INNER_PEER",
    "AETHER_WIW_OUTER_PEER",
    "AETHER_WIW_PEERS",
];
pub fn transport(protocol: &str) -> Option<(&'static str, bool, bool)> {
    Some(match protocol {
        "wireguard" => ("wg", false, false),
        "gool-classic" => ("gool", false, true),
        "gool-h3" => ("gool", false, false),
        "gool-h2" => ("gool", true, false),
        "mim-h3" => ("mim", false, false),
        "mim-h2" => ("mim", true, false),
        "masque-h3" => ("masque", false, false),
        "masque-h2" | "masque-h2-stormdns" => ("masque", true, false),
        _ => return None,
    })
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn managed_settings_cannot_override_the_vpn_contract() {
        for key in [
            "AETHER_CONFIG",
            "AETHER_SOCKS",
            "AETHER_MARK",
            "AETHER_REGISTER",
            "AETHER_TOR_BIND",
            "AETHER_PSIPHON_BIN",
        ] {
            assert!(!OPTIONS.contains(&key));
        }
        for key in [
            "AETHER_ROUTE_BLOCK",
            "AETHER_UPSTREAM",
            "AETHER_ECH",
            "AETHER_TOR",
            "AETHER_ACCESS_TOKEN",
        ] {
            assert!(OPTIONS.contains(&key));
        }
    }
    #[test]
    fn nested_protocols_choose_the_correct_carrier_and_mode() {
        assert_eq!(transport("gool-classic"), Some(("gool", false, true)));
        assert_eq!(transport("gool-h2"), Some(("gool", true, false)));
        assert_eq!(transport("gool-h3"), Some(("gool", false, false)));
        assert_eq!(transport("mim-h2"), Some(("mim", true, false)));
        assert_eq!(transport("mim-h3"), Some(("mim", false, false)));
    }
    #[test]
    fn unknown_protocols_fail_closed_and_stormdns_keeps_h2() {
        assert_eq!(transport("unknown"), None);
        assert_eq!(transport("masque-h2-stormdns"), transport("masque-h2"));
        assert_eq!(transport("wireguard"), Some(("wg", false, false)));
    }
}
