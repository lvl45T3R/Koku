# Koku v0.3.7

## DNS Hunter fallback

This release adds an opt-in DNS Hunter fallback in **Settings**. Koku tests
the default resolver first; if it cannot return a valid public response for
the check target, it tests a small bounded resolver set and uses the fastest
clean result as the Android VPN DNS resolver.

DNS Hunter is derived from the method in
[mirarr-app/network-checker](https://github.com/mirarr-app/network-checker)
at revision `f2a259b3e53449c512183baf6805c0e99ed83500`, licensed GPL-3.0.
The app setting, repository notice, attribution source, and unmodified GPL
text are included in this release source.

## Important limitation

Changing DNS affects hostname resolution. It cannot unblock a literal IP and
does not replace Koku's direct WARP gateway IP scan.

## Verification

`assembleRelease` completed successfully using the pinned local toolchain.

| APK | SHA-256 | Bytes |
| --- | --- | ---: |
| `Koku-v0.3.7-arm64-v8a.apk` | `04ca6fdad0411f15cc21b12c08b6232b1925f815e9c07074be94945d28a6c462` | 16,951,626 |
| `Koku-v0.3.7-armeabi-v7a.apk` | `0fbcc3ad300693dc43c6f0cab1e9b415abba45c17f0e86e390718cd36f85cd77` | 14,409,266 |
| `Koku-v0.3.7-universal.apk` | `41e17dfdacfaf594d497e299b25b7f425b87794bbf290532c04e75b8bfa1042f` | 38,399,436 |
