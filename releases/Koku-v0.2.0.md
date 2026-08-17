# Koku v0.2.0

Android ARM64 debug build with the selected Aether v1.3.0 transport updates.

## Changes

- Added optional Reliable/Ironclad gateway scanning with a real tunnel and
  end-to-end HTTP check.
- Kept Fast/Turbo scanning as the default.
- Added smart MASQUE and WireGuard reconnect behavior: retry the last
  known-good endpoint before a full rescan.
- Added cancellation cleanup for temporary scan tunnels.
- Corrected MASQUE HTTP/2 scan selection in the Android adapter.
- Updated the vendored engine source marker to `aether-v1.3.0-7af49ed`.

The upstream SOCKS5 LAN relay fix, CLI version flag, OpenWrt/musl build work,
and Termux packaging changes are intentionally excluded because they are not
used by Koku's Android TUN integration.

## Package

- Application ID: `io.github.lvl45t3r.koku`
- Version: `0.2.0` (`versionCode` 2)
- ABI: `arm64-v8a`
- Minimum Android version: Android 7.0 / API 24
- Signing: Android debug certificate
- APK: `Koku-v0.2.0-arm64-v8a-debug.apk`

## Verification

- Native ARM64 release compilation: passed with Rust 1.88 and Android NDK
  r26b (`26.1.10909125`).
- Kotlin compilation: passed.
- `assembleDebug`: passed.
- `lintDebug`: passed.
- APK Signature Scheme v2 verification: passed, with one debug signer.
- 16 KiB page and 4-byte ZIP alignment check: passed.
- Packaged native engine: `lib/arm64-v8a/libaether_android.so`.
- Unused `libquiche.so` and `libboringtun-*.so` cargo-ndk outputs: excluded.

APK size: `20,173,744` bytes.

SHA-256:

```text
3ae9ba2d37d2334af48486cf6444be521062f637f261f5aedbf5e7dae203fbec
```
