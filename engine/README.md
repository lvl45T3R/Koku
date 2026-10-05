# Vendored native engine source

This directory contains the source required to reproduce Koku's native Android
library.

- `aether/` contains the complete Aether 2.3.0 core used by the JNI adapter.
- `quiche/` contains the `quiche`, `octets`, and `qlog` workspace members used
  by that transport layer.
- `REVISION` identifies the pinned upstream commit and Android port.

The snapshot contains no Git metadata. Original license and copyright files
remain alongside the corresponding source. See PROVENANCE.md for embedding
patches and FEATURE_PARITY.md for Android behavior.
