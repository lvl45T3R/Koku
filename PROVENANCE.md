# Source provenance

Koku: https://github.com/lvl45T3R/Koku

| Component | Source | Pinned revision/version | License |
|---|---|---|---|
| Aether full core | https://github.com/CluvexStudio/Aether | 6175b67df370ab856bcee07fe85b524031903956 / 2.3.0 | AGPL-3.0 |
| quiche | Aether's quiche vendor tree at that commit | 0.29.3 | BSD-2-Clause |
| tun2proxy | https://github.com/tun2proxy/tun2proxy | 62109025df9b331ae9e5fca8e49f812eb6ce0747 / 0.8.4 | MIT |
| Psiphon helper | https://github.com/CluvexStudio/psiphon-tunnel-core/tree/83aa73b9b982e7421e00117f5b0c5aceb5dda452 | 83aa73b9b982e7421e00117f5b0c5aceb5dda452 | GPL-3.0 |
| Lyrebird helper | https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird | v0.0.0-20260312101154-fc105a03c0e0 | BSD-3-Clause / GPL-3.0 components |
| Arti/Tor | crates.io dependencies of Aether's tor feature | 0.46.0; native/Cargo.lock | MIT/Apache-2.0 and dependency licenses |
| DNS Hunter method/data | https://github.com/mirarr-app/network-checker | third_party/network-checker/UPSTREAM.md | GPL-3.0 |

The Aether source and quiche trees are fully vendored. The old selective 1.5
Android port is replaced by the full upstream library. The Android-specific
adapter now lives in native/src/lib.rs and native/src/settings.rs, with tun2proxy
as its TUN frontend. Existing identities remain under the previous private base
paths; the upstream core manages certificates, organization profiles, and
secondary identities.

The tun2proxy build script has one Android cross-build adjustment: its Windows
wintun copying code runs only for a Windows target, rather than any Windows host.
No transport logic is modified. Its license remains in third_party/tun2proxy.

Go helper sources use the exact commits pinned by Aether's build scripts. They
are compiled for Android, with PIE, NDK API 24, 16-KiB segment alignment and
`-checklinkname=0` for Psiphon's Android anet compatibility. Source archives and
revision/checksum evidence accompany the release; unmodified licenses are in
third_party/helpers. Rebuild commands are in scripts/build-helpers.ps1.

Rust dependencies are locked by native/Cargo.lock; Go modules are locked by the
helpers' go.mod/go.sum (Psiphon also retains its upstream vendor directory).
No credential, private WARP identity, signing key or downloaded tool is published.

The embedding adds two small Aether patches: SOCKS route/gateway settings can be
reset between VPN sessions, and resource profiles have separate caches so changing
low/medium/high takes effect on the next connection. The native adapter also
clears the previous session's Zero Trust token cache before installing new options.

For Android, the Aether dependency emits only its Rust library; the JNI crate
is the sole packaged core shared library. The upstream FFI code is retained.
The build statically links the C++ runtime using CXXSTDLIB=c++_static.
