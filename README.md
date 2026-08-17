# Koku

[فارسی](README.fa.md) | English

Koku is inspired by and built on Aether. It is not an independent clean-room
rewrite: its Android interface, `VpnService` integration, JNI boundary, and TUN
adapter were developed for Android, while its native transport engine compiles
selected Aether modules vendored in this repository.

The application uses `VpnService` for device routing and runs the Aether
transport engine through JNI.

Android application ID: `io.github.lvl45t3r.koku`

## Supported transports

- MASQUE over HTTP/3
- MASQUE over HTTP/2
- WireGuard

The current package targets `arm64-v8a` and IPv4 traffic.

## Gateway scan modes

- **Fast** uses Aether's Turbo handshake scan and remains the default.
- **Reliable** uses Aether's Ironclad scan. It opens a real tunnel and
  completes an end-to-end HTTP request before accepting a gateway.

Reliable mode takes longer, but avoids selecting endpoints that answer a
handshake without carrying real traffic.

The native worker also keeps the Android TUN active after a transport drop. It
rechecks the last known-good MASQUE or WireGuard endpoint first and only starts
a full scan when that endpoint is no longer usable.

The Android engine port now tracks Aether v1.5 transport fixes. It pins MASQUE
server certificates, detects silent WireGuard tunnels, avoids netstack stalls
under backpressure, validates tunnel DNS and HTTP probes, locks SOCKS UDP
associations to their client, and scans Cloudflare ranges and ports in the
documented order.

## Interface

The default Home view contains the connect control and latest tunnel status.
Diagnostics remain behind the Debug tab, where the log can be copied or
cleared. Protocol and gateway-scan selection are under Settings.

After the tunnel connects, the client checks the public exit address over
HTTPS and shows its country and request latency. The lookup parses Cloudflare's
lightweight `/cdn-cgi/trace` response, avoiding the rate-limited provider that
previously returned HTTP 429.

Settings also includes `NO-IR Exit`. When enabled, the native engine verifies
the candidate route through its isolated tunnel before bridging application
traffic, rejects Iranian or unverifiable exits, and tries up to four distinct
gateways. If none passes, the connection fails closed.

## Layout

```text
app/       Kotlin, Compose UI, VpnService, Android resources
engine/    Vendored Aether and quiche source required by the native build
native/    Rust JNI boundary and Android TUN adapter
```

The repository is self-contained: the native crate resolves all local source
and path dependencies from `engine/`. No parent checkout or external Git
submodule is required.

See [PROVENANCE.md](PROVENANCE.md) for the source relationship and
[NATIVE_ENGINE.md](NATIVE_ENGINE.md) for the integration boundary.

## Build

On the pinned Windows toolchain, build the native libraries and release APK with:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-release.ps1
```

Detailed requirements and Windows commands are in [BUILD.md](BUILD.md) and
[BUILD.fa.md](BUILD.fa.md). The exact verified versions, retained local tool paths,
checksums, source basis, and no-download repeat procedure are recorded in
[BUILD_TOOLCHAIN.md](BUILD_TOOLCHAIN.md).

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Published, checksum-documented builds are stored under `releases/`.

## License

The Aether-derived components are distributed under AGPL-3.0. The bundled
quiche source retains its BSD-2-Clause terms. See [NOTICE.md](NOTICE.md),
[PROVENANCE.md](PROVENANCE.md), [LICENSE](LICENSE), and the quiche license in
`engine/quiche/COPYING`.

## Optional support

| Network | Address |
| --- | --- |
| Ethereum and BNB Smart Chain | `0x95d6a55cbbd71c3c4d4f73cd51585b579b304252` |
| TRON | `TBhZy5L1LRbXdHh3PzJhipBrpPHvUVt25u` |
| Bitcoin | `bc1qtycnhk03ferdynmv32ekjkweuy725c7zntdf4z` |
