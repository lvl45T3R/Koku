# Koku v0.3.4

This release adds an opt-in non-Iranian exit guard and makes the connection status probe reliable across MASQUE HTTP/3, MASQUE HTTP/2, and WireGuard.

## Highlights

- `NO-IR Exit` verifies the candidate route before application traffic is bridged.
- Iranian and unverifiable exits are rejected, with up to four distinct gateways attempted.
- Route exhaustion fails closed instead of accepting an unsafe exit.
- The status card now uses Cloudflare trace instead of the provider that returned HTTP 429.
- The exact successful Windows toolchain and no-download repeat build are documented in `BUILD_TOOLCHAIN.md` and automated by `scripts/build-release.ps1`.

## Assets

| Asset | Bytes | Packaged ABI(s) | SHA-256 |
|---|---:|---|---|
| `Koku-v0.3.4-arm64-v8a.apk` | 16,935,242 | `arm64-v8a` | `2F9956BC0DC0D01D0035D12304F6EAD8CC93A65B8A64D93BCF3117A5D2152DEE` |
| `Koku-v0.3.4-armeabi-v7a.apk` | 14,392,882 | `armeabi-v7a` | `ECABC6040AC0067377F2992AF219F34FCED50CAC0A04B4579A560F684D3615AC` |
| `Koku-v0.3.4-universal.apk` | 38,383,052 | `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` | `42E821BC6F87AA89A12BC2E6223D17F3A3AEFD7ABA05353260AA3D39221C550D` |

## Verification

- Package: `io.github.lvl45t3r.koku`
- Version: `versionCode 7`, `versionName 0.3.4`
- Minimum SDK: 24; compile/target SDK: 35
- All three APKs pass Android `apksigner verify` using APK Signature Scheme v2.
- Signer certificate SHA-256: `a40be87cdf780dadedb746714df11ce8b8e8c80035af2559abc757c7278ea5e0`
- All three APKs pass `zipalign -c -P 16 -v 4`.
- Packaged native ABIs were enumerated and matched to each asset's intended scope.
- Android release lint and Kotlin compilation passed.
- Native release compilation passed for all four packaged ABIs.
- No Android device or emulator was attached, so live carrier/VPN exit behavior was not exercised in this build session.
