# Koku v0.4.0 — Aether 2.3.0

Koku now embeds the complete Aether 2.3.0 core from commit
`6175b67df370ab856bcee07fe85b524031903956`, rather than the older selective transport adapter.

## Changes

- MASQUE H2/H3, WireGuard, classic gool, WireGuard inside MASQUE H2/H3, and nested MASQUE H2/H3.
- Tor/Arti and Psiphon inside, outside and standalone modes. Lyrebird includes obfs4, obfs3, scramblesuit, meek, Snowflake and WebTunnel support; bridge/server configuration can be imported.
- Block/direct domain, IP and port rules; HTTP/TLS name sniffing; upstream proxies; TLS fragmentation, QUIC v2, ECH, cipher/group and enrollment API controls.
- Zero Trust service credentials/JWT, email-code request/resend/sign-in, and Gateway settings.
- Five scan modes, saved protocol/scan preferences, reconnect and saved-gateway validation, resource profiles and timeout controls.
- IPv4/IPv6 Android TUN, DNS over tunneled TCP, actual packet/byte statistics, clean cancellation and service cleanup. NO-IR verifies and monitors the final carrier.
- Existing DNS Storming, per-app routing, diagnostics and exit display are retained. The updater selects a compatible ABI or the universal APK.

[Detailed feature audit](https://github.com/lvl45T3R/Koku/blob/v0.4.0/FEATURE_PARITY.md), [source provenance](https://github.com/lvl45T3R/Koku/blob/v0.4.0/PROVENANCE.md), and [rebuild instructions](https://github.com/lvl45T3R/Koku/blob/v0.4.0/BUILD_TOOLCHAIN.md) are included in the repository.

## Downloads and source

- `Koku-v0.4.0-arm64-v8a.apk`: ARM64 devices.
- `Koku-v0.4.0-armeabi-v7a.apk`: ARMv7 devices.
- `Koku-v0.4.0-universal.apk`: all four ABIs, including x86 and x86_64.
- Psiphon source archive: exact commit `83aa73b9b982e7421e00117f5b0c5aceb5dda452`, including its vendor tree.
- Lyrebird source archive: exact Go module `v0.0.0-20260312101154-fc105a03c0e0`, including go.mod/go.sum.
- `Koku-v0.4.0-SHA256SUMS.txt`: checksums for all downloadable binaries and helper sources.

The APKs use application ID `io.github.lvl45t3r.koku`, versionCode 13, minSdk 24 and targetSdk 35, and the existing Koku release signing identity. Native binaries are packaged for extraction; ELF LOAD segments are aligned for 16KB pages. Helper executables retain their Go metadata. License texts are bundled in APK assets.

## Validation

- 37 contract tests pass against the vendored routing/name-sniffing code, Android protocol settings and resource-profile session regression. Regression tests ensure local ACK/RST/DNS packets cannot confirm TCP data.
- Android compilation and lint pass.
- Emulator: native loading, VPN start/stop/restart, TUN traffic counters and the app's TCP payload traffic check; both helper version commands execute on Android.
- Release verification: signatures, version, ABI contents, native-library hashes, ELF dependencies/alignment and APK zip alignment.

Emulator traffic-counter confirmation is not an exhaustive reachability test. Every Tor/Psiphon transport, CDN, bridge, organization and censored-network combination has not been exercised. Zero Trust requires real organization credentials; no sign-in email was sent during verification. Tor/Psiphon exits carry TCP. One optional final carrier is selected per session. Desktop installation, root firewall marks and terminal prompts remain platform interfaces; their Android mappings and limits are documented in the feature audit.
