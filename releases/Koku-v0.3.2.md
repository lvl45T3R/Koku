# Koku v0.3.2

This release adds in-app update discovery and repository access.

## Changes

- Added a Settings action that checks GitHub Releases for the latest Koku APK.
- Downloads a newer ARM64 APK and opens Android's package installer when an
  update is available.
- Added a direct repository link from Settings.
- Made Settings scrollable so protocol, per-app proxy, update, and repository
  controls fit on smaller devices.

## Validation

- Built with the Windows Gradle wrapper against the configured Android SDK.
