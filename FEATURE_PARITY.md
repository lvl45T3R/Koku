# Aether 2.3.0 feature integration audit

Reference: https://github.com/CluvexStudio/Aether/tree/6175b67df370ab856bcee07fe85b524031903956

| Reference capability | Android integration |
|---|---|
| Automatic endpoint discovery and end-to-end validation | Full upstream prober, validated tunnels, Turbo/Balanced/Thorough/Verified/Ironclad UI |
| MASQUE H3 and H2 | Protocol choices; full quiche 0.29.3 / BoringSSL 5.2 source |
| WireGuard | Protocol choice; full upstream data-plane/staleness handling |
| Gool over MASQUE | H2 and H3 choices; optional inner peer |
| Classic nested WireGuard | Classic gool choice; independently supplied/scanned outer/inner hops |
| MASQUE inside MASQUE | H2/H3 choices; independently supplied/scanned hops |
| Traffic obfuscation | Per-network fallback plus explicit advanced profile |
| H2 fragmentation and QUIC v2 | Advanced fields; current upstream defaults |
| ECH and resolver fronting | auto/base64 ECH, resolver URL/domain, @address/@sni syntax |
| TLS fingerprint controls | Groups, ciphers, GREASE and verification fields |
| API fragmentation and enrollment-address override | Advanced fields |
| Block/direct routing | Domain, address/CIDR, port, keyword and regex rules; TLS/HTTP name sniffing; imported rule file |
| Upstream SOCKS/HTTP proxy | Advanced URL field; upstream TCP/UDP handling remains in the core |
| Local SOCKS5 and HTTP CONNECT | Loopback WARP 1819/1830; Tor 1820/1831; Psiphon 1821/1832 (for enabled chain/reverse carriers); standalone carrier SOCKS uses 1819 |
| Tor inside/outside/alone | Carrier controls; Arti feature compiled in; final carrier selected for the VPN |
| Tor bridge discovery and custom bridges | Bridge/relay fields, imported bridge file, embedded Lyrebird obfs4/obfs3/scramblesuit/meek_lite/snowflake/webtunnel |
| Psiphon inside/outside/alone | Carrier controls; embedded executable from upstream's pinned source |
| Psiphon CDN/direct/auto and region | Advanced transport/region/CDN IP/SNI/list fields; imported config/server entries |
| Zero Trust / organization gateway | Team, service credentials/JWT, in-app email code request/resend/submit, optional Gateway |
| Automatic reconnect and quick reconnect | Full core supervisor and lastconn persistence; Android TUN stays up during retries |
| Exit-country policy | Allow/deny country field; existing NO-IR guard also verifies and monitors the final proxy |
| CPU/RAM tuning, keepalive and timeouts | Advanced fields; resource selection updates between VPN sessions |
| Diagnostic detail and statistics | Existing in-app debug log and actual TUN packet/byte counters; native log level field |
| Existing Android features | DNS Storming and network-checker attribution, app selection, external test and exit status retained; updater chooses a compatible ABI or universal APK |

Android chooses one optional final carrier (Tor or Psiphon) per VPN session.
Tor/Psiphon final exits carry TCP, so a UDP-dependent application needs a WARP
exit. Tor/Psiphon reverse modes reject WireGuard/classic gool as upstream does;
choose MASQUE when the carrier needs TCP.

The desktop install scripts, Docker/OpenWrt packaging, interactive terminal
prompts and root-only firewall marks are platform interfaces rather than Android
VPN features. Android owns listener/config paths and provisions identities
automatically. The complete CLI and C embedding API are retained in the vendored
core; arbitrary CLI commands cannot override the VPN lifecycle.

## Verification scope

The contract suite runs the actual vendored routing/sniffing code, the actual
Android protocol mapping, and a regression check for resource profiles changing
between sessions. Compilation/lint and APK signature/ABI/hash checks are recorded
in the release notes. Emulator smoke checks establish app/native startup; they do
not establish every transport, country, CDN, organization or censored-network
combination. Live Tor, Psiphon and Zero Trust sign-in depend on network reachability
and, for organizations, real credentials.
