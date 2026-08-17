# Changelog

All notable user-facing changes are recorded here. Per-release APK hashes and verification evidence live under `releases/`.

## [0.3.8] - 2026-08-17

### Added

- Replaced the binary DNS Hunter switch with **DNS Storming**: Default, Public Hunter, Iranian Hunter, and Custom DNS modes.
- Added the unmodified Iranian ISP CIDR source from network-checker. Iranian Hunter samples a bounded set of addresses and accepts only resolvers that answer a validated DNS query.
- Added Custom DNS input for one or more manually supplied IPv4 resolver addresses.

### Notes

- Iranian CIDRs are scan targets, not an assertion that every address is a DNS resolver. The scan is limited to 128 sampled addresses to keep VPN startup bounded.
- Release version is now `versionCode 11` / `versionName 0.3.8`.

## [0.3.7] - 2026-08-17

### Added

- Added the opt-in DNS Hunter fallback. Koku validates the default resolver first and, only when that fails, tests a bounded set of public resolvers and applies the fastest clean result to Android VPN DNS.
- Added in-app attribution for the DNS Hunter method from mirarr-app/network-checker, including the upstream revision, unmodified GPL-3.0 text, source link, and third-party notice.

### Notes

- DNS changes hostname resolution only; they cannot make a literal, blocked IP reachable or replace Koku's WARP gateway IP scan.
- Release version is now `versionCode 10` / `versionName 0.3.7`.

## [0.3.6] - 2026-08-16

### Added

- Added the distinct `H2 StormDNS` connection option. It uses the MASQUE HTTP/2/TCP native transport, including gateway validation and the optional `NO-IR Exit` guard.

### Changed

- Release version is now `versionCode 9` / `versionName 0.3.6`.

## [0.3.5] - 2026-08-16

### Added

- Added the opt-in `NO-IR Exit` guard for MASQUE HTTP/3, MASQUE HTTP/2, and WireGuard.
- Added native pre-bridge country verification with four distinct route attempts and fail-closed behavior.
- Added a pinned Windows build and GitHub release runbook so future releases reuse the retained toolchain and known authentication workaround.

### Fixed

- Replaced the rate-limited `ipwho.is` exit lookup that returned HTTP 429 with Cloudflare trace parsing.
- Preserved the verified exit state when the native tunnel transitions to connected.

### Changed

- Release version is now `versionCode 8` / `versionName 0.3.5`.

## [0.3.4] - 2026-08-16

### Added

- Added the optional `NO-IR Exit` connection setting for MASQUE HTTP/3, MASQUE HTTP/2, and WireGuard.
- Added native pre-bridge exit verification with up to four distinct candidate routes.
- Added fail-closed behavior when every candidate is Iranian or its country cannot be verified.
- Added a pinned, reusable Windows build toolchain record and no-download build script.

### Fixed

- Replaced the rate-limited `ipwho.is` status lookup, which could return HTTP 429, with Cloudflare trace parsing.
- Routed NO-IR verification through the candidate tunnel's native netstack so it works consistently across all three transports.

### Changed

- Release version is now `versionCode 7` / `versionName 0.3.4`.

## Earlier releases

See the versioned notes in `releases/Koku-v0.3.3.md` and older files.
