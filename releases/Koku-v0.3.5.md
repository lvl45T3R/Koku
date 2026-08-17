# Koku v0.3.5

This release adds a fail-closed non-Iranian exit guard and fixes the exit status probe across MASQUE HTTP/3, MASQUE HTTP/2, and WireGuard.

## Highlights

- Adds `NO-IR Exit` to connection settings.
- Verifies the candidate route through the native tunnel before application traffic is bridged.
- Rejects Iranian and unverifiable exits and tries up to four distinct gateways.
- Replaces the rate-limited exit lookup that returned HTTP 429 with Cloudflare trace.
- Keeps the verified native exit IP/country visible after the tunnel reaches Connected.
- Adds a pinned, no-download repeat build and GitHub publication runbook.

## Verification

| Asset | Bytes | Packaged ABI(s) | SHA-256 |
|---|---:|---|---|
| `Koku-v0.3.5-arm64-v8a.apk` | 16,935,242 | `arm64-v8a` | `45E88ACCD0CE1C50C7E923EA6101A969C90591728284396DA8A1AC2E95D44F90` |
| `Koku-v0.3.5-armeabi-v7a.apk` | 14,392,882 | `armeabi-v7a` | `97C49006CC422D33513AD7E700DC5FDD174DC89F8087B38F97CF6601A45BE28D` |
| `Koku-v0.3.5-universal.apk` | 38,383,052 | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` | `02D8619B2A0B57D654AC78C6D49F01673C0DB9EEAA2A1D39172DB533530FBE05` |

- Package: `io.github.lvl45t3r.koku`
- Version: `versionCode 8`, `versionName 0.3.5`
- Minimum SDK: 24; compile/target SDK: 35
- Android release lint passed.
- Native release compilation had already passed for all four packaged ABIs; this version-only rebuild reused those verified libraries.
- All APKs pass `apksigner verify` with APK Signature Scheme v2.
- Signer certificate SHA-256: `a40be87cdf780dadedb746714df11ce8b8e8c80035af2559abc757c7278ea5e0`
- All APKs pass `zipalign -c -P 16 4`.
- Packaged ABI contents match the dedicated/universal asset names.
- No Android device or emulator was attached, so live carrier/VPN exit behavior was not exercised in this build session.
