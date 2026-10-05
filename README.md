# Koku

[فارسی](README.fa.md) | English

Koku is an Android VPN built on the complete Aether 2.3.0 core, pinned at
`6175b67df370ab856bcee07fe85b524031903956`. Application ID: `io.github.lvl45t3r.koku`.

## Connections

- MASQUE HTTP/3 (QUIC), HTTP/2, and the existing H2 StormDNS preset.
- WireGuard, WireGuard inside MASQUE H2/H3, classic WireGuard inside WireGuard (gool), and MASQUE inside MASQUE H2/H3.
- Turbo, Balanced, Thorough, Verified and Ironclad endpoint discovery; automatic reconnect and persisted last-good gateway validation.
- Obfuscation profiles, HTTP/2 ClientHello fragmentation, QUIC v2 opening, Chrome-style TLS, optional ECH and custom enrollment addresses.
- Tor and Psiphon inside WARP, outside WARP (MASQUE H2), or alone. Tor includes the Lyrebird bridge helper; Psiphon includes its Android executable and CDN-fronting options.
- Domain, IP/CIDR, port, keyword and regular-expression routing with block/direct rules and TLS name sniffing behind the VPN.
- SOCKS5/HTTP upstream proxies; local SOCKS5 at `127.0.0.1:1819` and HTTP CONNECT at `127.0.0.1:1830`.
- Zero Trust enrollment by service token, enrollment JWT, or an in-app email-code sign-in; optional organization gateway.

## Android features

The VPN handles IPv4 and IPv6 through tun2proxy. The app excludes itself from its
VPN to keep the core's outgoing connections out of the tunnel. DNS Storming,
per-app include/bypass rules, diagnostics, exit information and the updater remain
available. VPN DNS requests use TCP through the selected proxy.

Settings contains protocol and scan choices, Tor/Psiphon carrier controls, and
advanced fields. Protocol/scan selections persist across app restarts. Disconnect
before changing connection settings. Tor and Psiphon exits carry TCP; UDP
applications should use a WARP exit.

**NO-IR Exit** applies the core's country policy before WARP opens its proxy. Koku
also verifies the final selected proxy before forwarding Android packets, and
checks it again every 60 seconds. An Iranian or unverifiable final exit stops the
worker and closes the VPN. The interface stays in the stopping state until the
native runtime has shut down.

See [FEATURE_PARITY.md](FEATURE_PARITY.md) for the integration audit and platform
boundaries, [NATIVE_ENGINE.md](NATIVE_ENGINE.md) for the packet path, and
[PROVENANCE.md](PROVENANCE.md) for source revisions and licenses.

## Build

The native crate depends on the full vendored Aether/quiche source and the
vendored tun2proxy library. The retained Windows build uses Rust 1.98.0, NDK r26b
and Go 1.26.8. With the pinned tools and helper sources installed:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-helpers.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-release.ps1
```

Signing uses the four `KOKU_SIGNING_*` environment variables. Never commit a key,
password, WARP identity, organization secret or local tool cache. Details are in
[BUILD_TOOLCHAIN.md](BUILD_TOOLCHAIN.md). Releases contain signed APKs and hashes.

## License

Koku and Aether are AGPL-3.0. Preserve their source and attribution when distributing
binaries. Third-party notices and licenses are in [NOTICE.md](NOTICE.md) and
`third_party/`.
