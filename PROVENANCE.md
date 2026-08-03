# Source provenance

## Repository relationship

Koku is a standalone Android repository. Its Android interface, `VpnService`
integration, JNI boundary, and TUN adapter are maintained here. The native
transport layer reuses selected Aether modules and Cloudflare quiche source,
both vendored under `engine/`.

| Role | Repository |
| --- | --- |
| Koku Android repository | `https://github.com/lvl45T3R/Koku.git` |
| Aether upstream project | `https://github.com/CluvexStudio/Aether.git` |
| quiche upstream project | `https://github.com/cloudflare/quiche.git` |

The vendored snapshot is intentionally stored without upstream Git metadata.
`engine/REVISION` records the upstream Aether release and commit used by the
Android build. The current transport port is based on Aether `v1.5.0` at
`66a798b`. It selectively carries the transport, probe, and resolver fixes
used by the Android adapter; the upstream CLI-only API front, routing-rule
menu, and Zero Trust enrolment flow are intentionally not exposed here.
The upstream quiche 0.29.3 vendor refresh is also deferred until the Android
Rust/NDK toolchain can compile and exercise that much larger dependency bump.

## Reused source

`native/src/lib.rs` includes the required Aether modules from
`engine/aether/src`. This covers endpoint discovery, account provisioning,
MASQUE, HTTP/2, WireGuard, TLS, fragmentation, and obfuscation behavior.
It also includes Aether's Ironclad tunnel probe, MASQUE SPKI certificate
pinning, reconnect task cleanup, WireGuard dead-tunnel detection, adaptive
runtime tuning, netstack backpressure fixes, authenticated DNS replies,
source-locked SOCKS UDP associations, and the corrected v1.5 endpoint scan
order.

`native/Cargo.toml` resolves the local transport dependencies from the same
repository:

```toml
quiche = { path = "../engine/quiche/quiche" }
octets = { path = "../engine/quiche/octets" }
```

The native build script watches these vendored directories and embeds the
source identifier from `engine/REVISION` in `libaether_android.so`.

## Android-specific code

The following code is specific to this client and is not taken from the Aether
command-line interface:

- Kotlin Compose interface and connection state
- Android `VpnService` lifecycle and foreground notification
- JNI lifecycle and log callback
- Android TUN descriptor handling
- IPv4 address and transport-checksum translation
- Traffic counters and external-browser connection test
- Three-section Compose interface and launcher artwork integration
- Post-connect public exit-address and country check
- Android TUN-preserving transport reconnect supervisor
- Fast and Reliable scan-mode selection

The Aether command-line interface remains SOCKS5-based. The Android adapter
replaces that boundary with direct packet I/O while reusing the transport
implementations.

## Third-party source

Aether is developed by CluvexStudio and distributed under AGPL-3.0. The
vendored quiche source is developed by Cloudflare and retains its BSD-2-Clause
license and copyright notices.

Only the quiche workspace members required for the Android native library are
vendored: `quiche`, `octets`, and `qlog`. Unrelated examples, test
certificates, applications, fuzzing corpora, and tools are not included.

Public exit-address metadata is requested from the free `ipwho.is` HTTPS API.
The Android system download service performs that request so it follows the VPN
route while the Koku package itself remains excluded to prevent a socket loop.
The displayed latency is the end-to-end HTTPS probe duration, not raw ICMP
round-trip time.
