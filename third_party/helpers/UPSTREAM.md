# Bundled Android helpers

These sources are unmodified; Android compilation/packaging is local.

- Psiphon: CluvexStudio fork of Psiphon tunnel core, pinned by Aether at
  `83aa73b9b982e7421e00117f5b0c5aceb5dda452`, GPL-3.0.
  https://github.com/CluvexStudio/psiphon-tunnel-core/tree/83aa73b9b982e7421e00117f5b0c5aceb5dda452
- Lyrebird: Tor Project, `v0.0.0-20260312101154-fc105a03c0e0`.
  https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/-/tree/fc105a03c0e0
  BSD-3-Clause, with GPL-3.0 components. Both license texts are retained here.

The corresponding helper source archives are attached to the Koku v0.4.0 release,
including Psiphon's vendor tree and Lyrebird's go.mod/go.sum. It contains no
private identity or signing material. Android binaries are PIE and installed in
nativeLibraryDir as libpsiphon.so and liblyrebird.so. Their paths are private
managed settings, not downloaded executable paths. Rebuild using
scripts/build-helpers.ps1 and the toolchain instructions.
