# Koku v0.3.6

This release adds the missing `H2 StormDNS` protocol choice to Connection settings.

## Highlights

- Adds a separate `H2 StormDNS` choice alongside MASQUE H3, MASQUE H2, and WireGuard.
- Maps the selection to the native MASQUE HTTP/2/TCP transport rather than treating it as an H3 connection.
- Keeps gateway validation and the optional fail-closed `NO-IR Exit` verification active for this mode.

## Verification

| Asset | Bytes | Packaged ABI(s) | SHA-256 |
|---|---:|---|---|
| `Koku-v0.3.6-arm64-v8a.apk` | 16,935,242 | `arm64-v8a` | `BBD19E66D40EFB51E2F3ACB053F3F4EE57A90149159208946D03174105A66778` |
| `Koku-v0.3.6-armeabi-v7a.apk` | 14,392,882 | `armeabi-v7a` | `64BB1DB14CB8A2A8F0A28C8CBAD9FC326D3C03D9C0C58983D3DEE13901CCD045` |
| `Koku-v0.3.6-universal.apk` | 38,383,052 | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` | `704D662FC41673AA2580A58114BEC701DB339F393ECFC6AF7385C44B26E0C746` |

- Package: `io.github.lvl45t3r.koku`
- Version: `versionCode 9`, `versionName 0.3.6`
- Android release lint passed.
- Each APK passes APK Signature Scheme v2 verification and `zipalign -c -P 16 4`.
- Packaged ABI contents match the dedicated/universal asset names.
- No Android device or emulator was attached, so live carrier/VPN behavior was not exercised in this build session.
