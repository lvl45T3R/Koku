# Native engine

## Integration model

`native` is an Android-specific Rust `cdylib`. Its JNI and TUN code is local to
this directory; transport and account behavior comes from the vendored Aether
source under `engine/aether`.

The crate includes these parent modules at compile time:

```text
engine/aether/src/account.rs
engine/aether/src/aethernoize.rs
engine/aether/src/config.rs
engine/aether/src/consts.rs
engine/aether/src/error.rs
engine/aether/src/fragment.rs
engine/aether/src/masque.rs
engine/aether/src/masque_h2.rs
engine/aether/src/netstack.rs
engine/aether/src/noize.rs
engine/aether/src/prober.rs
engine/aether/src/quic.rs
engine/aether/src/socks.rs
engine/aether/src/tls.rs
engine/aether/src/tunnelping.rs
engine/aether/src/wg_prober.rs
engine/aether/src/wireguard.rs
```

Cargo resolves `quiche`, `octets`, and `qlog` from `engine/quiche`.

## JNI boundary

Kotlin loads `libaether_android.so` through `AetherNative`.

```kotlin
nativeStart(configJson: String, tunFd: Int, logSink: NativeLogSink): Long
nativeStop(handle: Long)
```

`nativeStart` receives the detached Android TUN descriptor, validates the JSON
configuration, starts a Tokio runtime, and returns a non-zero lifecycle handle.
The callback forwards Rust log records to the application diagnostic view.

## Packet path

```text
Android application
    -> VpnService TUN (172.31.19.2/32)
    -> native TUN reader
    -> IPv4 address and checksum translation
    -> Aether MASQUE or WireGuard transport
    -> native TUN writer
    -> Android application
```

The Android package is excluded from its own VPN route. This keeps the outer
Aether sockets on the underlying network and prevents a routing loop. The
connection test therefore opens Google in an external browser and confirms
traffic using the TUN counters.

The exit-address check is submitted through Android's system download service,
which is outside the excluded application package and therefore follows the
active VPN route. It parses Cloudflare's lightweight `/cdn-cgi/trace` response,
and its completion time is reported as HTTPS latency. The temporary response
file and download record are removed after parsing.

When `NO-IR Exit` is enabled, verification happens natively before the bridge
starts. The same isolated MASQUE or WireGuard netstack requests Cloudflare
trace over the candidate tunnel. Iranian exits and routes whose country cannot
be verified are rejected; the scanner excludes them and tries up to four
distinct candidates. Exhaustion is fail-closed, so application traffic is not
bridged through an Iranian or unverified exit.

## State and storage

The foreground service owns the TUN descriptor and native handle. The native
engine stores separate MASQUE and WireGuard identities under the application's
private files directory. Android backup and device transfer are disabled for
that data.

The TUN descriptor survives transport reconnects. When MASQUE or WireGuard
ends, the native worker waits two seconds, validates the last known-good
endpoint, and reconnects to it when possible. A full scan is only used after
that validation fails.

Ironclad scan probes run an isolated Aether netstack and real HTTP request.
Each temporary probe tunnel is wrapped in an abort guard, so cancellation or a
timeout terminates its background task.

MASQUE HTTP/2 and HTTP/3 connections verify the server certificate against the
SPKI pins shipped by Aether. WireGuard sends periodic data-plane probes;
if no valid response arrives within the stale timeout, the transport ends and
the Android reconnect supervisor takes over. Reader, driver, and worker tasks
are aborted when their transport exits so repeated reconnects do not accumulate
idle work. Scan concurrency, socket buffers, netstack buffers, and channel
sizes are selected from the device CPU and memory profile.

## Current scope

- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`
- VPN payload: IPv4
- Transports: MASQUE HTTP/3, MASQUE HTTP/2, WireGuard
- Scan modes exposed by the app: Turbo and Ironclad
- WARP-in-WARP: not exposed by this Android adapter
- Aether v1.5 API-front, routing-rule UI, and Zero Trust enrolment: not exposed
- quiche 0.29.3 vendor refresh: deferred pending native Rust/NDK validation
