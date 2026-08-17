# Koku v0.3.3

This release updates the vendored Aether engine to the selectively ported v1.5.0 codebase and ships signed Android builds for 64-bit ARM, 32-bit ARM, and a universal package.

## Highlights

- Loads the per-app package list only when the app picker dialog is opened.
- Remembers the last working obfuscation profile per transport and network ASN.
- Tries the cached profile first and falls back across supported profiles when needed.
- Adds adaptive native resource tuning and selected Aether reliability improvements.
- Includes separately signed `arm64-v8a`, `armeabi-v7a`, and universal APKs.

## Verification

- Android lint passed.
- Release APK signatures were verified with `apksigner`.
- Native libraries in each APK were checked against their build artifacts.
