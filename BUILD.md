# Build

## Requirements

- JDK 17
- Android SDK Platform 35
- Android Build Tools
- Android NDK r26b or a compatible newer NDK
- Rust 1.88 or newer
- `cargo-ndk`

The repository includes the required native sources under `engine/`. It can be
built from a standalone clone and does not require a parent checkout.

## Native library

Set the NDK location, then build the ARM64 library:

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r26b
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
