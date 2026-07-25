# Koku v0.3.0

Android ARM64 debug build with the Aether v1.4.0 transport and reliability
updates.

## Changes

- Added SPKI certificate pinning for MASQUE HTTP/2 and HTTP/3 connections to
  reject untrusted server certificates.
- Added WireGuard data-plane health probes and stale-tunnel detection so a
  silent tunnel triggers the Android reconnect supervisor.
- Added deterministic cleanup for MASQUE socket readers, the HTTP/2 driver,
  and WireGuard worker tasks when a transport ends or reconnects.
- Added adaptive scan concurrency, socket buffers, netstack buffers, and
  channel capacities based on device CPU and memory.
- Moved per-packet and probe-retry noise to trace-level logging.
- Updated the vendored engine source marker to `aether-v1.4.0-ee5a5f5`.

The upstream CLI log-level interface, WARP-in-WARP reconnect loop, and
OpenWrt/musl release packaging are not exposed by Koku's Android TUN adapter.

## Package

- Application ID: `io.github.lvl45t3r.koku`
- Version: `0.3.0` (`versionCode` 3)
- ABI: `arm64-v8a`
- Minimum Android version: Android 7.0 / API 24
- Target Android version: API 35
- Signing: Android debug certificate
- APK: `Koku-v0.3.0-arm64-v8a-debug.apk`

## Verification

- Native ARM64 release compilation: passed with Rust 1.88 and Android NDK
  r26b (`26.1.10909125`).
- JNI exports `nativeStart` and `nativeStop`: verified.
- Native ELF dependencies: Android system libraries only.
- Kotlin/Java compilation and `assembleDebug`: passed.
- `lintDebug`: passed with four non-blocking compatibility/resource warnings.
- APK Signature Scheme v2 verification: passed, with one debug signer.
- 16 KiB ELF page alignment and APK ZIP alignment: passed.
- Packaged native engine: `lib/arm64-v8a/libaether_android.so`.
- Unused `libquiche.so` and `libboringtun-*.so` cargo-ndk outputs: excluded.

APK size: `20,206,512` bytes.

SHA-256:

```text
0c882401aac820755adf898b9899a44c3efab4c3c5304458fc5832095c4d2358
```
