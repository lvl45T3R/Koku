# Koku v0.3.1

This release improves the Android VPN control surface.

## Changes

- Tapping the main button while the tunnel is connecting now stops the startup
  attempt instead of doing nothing.
- Added per-app proxy settings with all-apps, selected-only, and bypass-selected
  modes.
- Applies selected app routing through Android `VpnService.Builder` so the TUN
  interface is established with the requested app allowlist or bypass list.

## Validation

- Built with the Windows Gradle wrapper against the configured Android SDK.
