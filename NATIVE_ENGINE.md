# Native Android integration

## Source boundary

`native` links the full `aether` library with its `tor` feature. Its local code
owns JNI, Android logging, lifecycle, settings and the TUN/SOCKS frontend.
`engine/REVISION` records the pinned upstream snapshot. Aether/quiche retain the
upstream implementation of discovery, account provisioning, TLS, QUIC, H2,
WireGuard, nested hops, Tor, Psiphon, routing, DNS and reconnects.

## Packet path

```
Android app -> VpnService TUN -> tun2proxy TCP/UDP session
            -> local SOCKS5 -> Aether routing and selected exit
            -> destination
```

The reverse path returns through the same userspace session to TUN. IPv4 and
IPv6 routes are installed. The frontend counts actual TUN reads/writes for the
existing diagnostic counters, uses MTU 1280 and TCP MSS 1240, and carries DNS
queries through SOCKS over TCP. The Koku package bypasses its own VPN; this also
lets explicit direct routing rules and external proxy connections reach the
underlying network without a routing loop.

The default proxy is 127.0.0.1:1819. Tor-inside uses 127.0.0.1:1820 and
Psiphon-inside uses 127.0.0.1:1821 as the VPN frontend. The WARP HTTP CONNECT
listener is 127.0.0.1:1830. All listeners remain loopback-only.

## Lifecycle and state

JNI takes ownership of the detached TUN fd. A single exclusive native worker
prevents two sessions from changing the process-wide Aether environment at once.
Each session clears prior Aether options and uses private persistent identity
paths. The core is polled concurrently with the TUN frontend and a stop receiver.
The frontend starts after a successful SOCKS greeting. Aether opens its WARP
listener only after tunnel validation and its exit policy have passed.

Stop cancels the frontend, drops the core and then drops the dedicated Tokio
runtime, aborting its remaining tasks. Upstream Psiphon uses kill-on-drop for its
child. A terminal worker error notifies the service to close the VPN and remove
the foreground notification; the UI retains the failed status. A stopping worker
must finish before another one can start. Transient transport failures show
reconnecting; resumed packet replies restore connected status.

NO-IR additionally probes the selected final proxy before any Android packets
are forwarded. It fails closed when the final country is IR or unavailable, and
monitors that proxy every 60 seconds. Aether separately checks WARP exit changes.

## Settings and helpers

Advanced fields map to an allowlist of upstream AETHER_* settings. Identity
paths, listener addresses, socket marks and standalone registration commands
cannot override the VPN contract. Credentials are private app preferences and
are not included in the startup configuration log. Android backup is disabled.

Native Zero Trust JNI exposes only the upstream email-code session and job APIs;
Kotlin polls cancellable jobs on its IO dispatcher and releases job/session
handles. Explicit service-token and JWT fields remain available.

Psiphon and Lyrebird are Android PIE executables packaged as `libpsiphon.so` and
`liblyrebird.so` with legacy native extraction and without AGP stripping. Their
paths come from nativeLibraryDir. Lyrebird is registered for obfs4, obfs3,
scramblesuit, meek_lite, snowflake and webtunnel. APK rebuilds require the matching
helpers for every selected ABI.

See FEATURE_PARITY.md and releases/Koku-v0.4.0.md for checks and limitations.

Exit status comes from the selected final SOCKS proxy, independently of Android
DownloadManager and per-app rules. TCP payload counters exclude local ACK/RST and
UDP/DNS packets from the connection-test verdict. Info-level lifecycle/statistics
events remain enabled even when a lower diagnostic verbosity is requested.
