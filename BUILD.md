# Build

> Windows release builds must use the pinned, already-provisioned toolchain and
> no-download script in [BUILD_TOOLCHAIN.md](BUILD_TOOLCHAIN.md). That document
> records the exact successful versions, checksums, source basis, signing inputs,
> and verification commands. The generic commands below are retained for other
> hosts only.

## Requirements

- JDK 17
- Android SDK Platform 35
- Android Build Tools
- Android NDK r26b or a compatible newer NDK
- Rust 1.98 or newer
- `cargo-ndk`

The repository includes the required native sources under `engine/`. It can be
built from a standalone clone and does not require a parent checkout.

## Native library

Set the NDK location, then build the ARM64 library:

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r26b
export CXXSTDLIB=c++_static
export BORING_BSSL_RUST_CPPLIB=c++_static
cd native
cargo ndk -t arm64-v8a -P 24 -o ../app/src/main/jniLibs build --release
```

Required output:

```text
app/src/main/jniLibs/arm64-v8a/libaether_android.so
```

`libaether_android.so` is self-contained apart from Android system libraries.
If `cargo-ndk` also copies `libquiche.so` or a `libboringtun-*.so` file, those
files are not runtime dependencies and should not be packaged.

## APK

Linux or macOS:

```bash
./gradlew assembleDebug
```

Windows:

```powershell
.\gradlew.bat assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Verification

```bash
./gradlew lintDebug
apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk
zipalign -c 4 app/build/outputs/apk/debug/app-debug.apk
```

Install on a connected ARM64 device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Release

`assembleRelease` produces an unsigned APK unless a signing configuration is
provided:

```bash
./gradlew assembleRelease
```

Signing keys and tunnel identity files must not be committed.

## Published build

After verification, copy the APK to `releases/` with a versioned filename and
record its SHA-256 in the matching release notes. Repository APKs are debug
signed unless the release notes explicitly state otherwise.

## Bundled carrier executables (0.4.0+)

Build the pinned Psiphon and Lyrebird sources for every ABI before packaging.
On Windows use `scripts/build-helpers.ps1` with Go 1.26.8 and the source locations
recorded in BUILD_TOOLCHAIN.md, then `scripts/build-release.ps1`. Each ABI needs
`libaether_android.so`, `libpsiphon.so`, and `liblyrebird.so`. The latter two are
PIE executables named as native libraries so Android extracts them into its
executable native-library directory. Do not strip the Go carrier executables.
The core statically links the C++ runtime; ELF LOAD segments use 16KB alignment.
Corresponding source archives and licenses accompany the release.