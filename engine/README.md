# Vendored native engine source

This directory contains the source required to reproduce Koku's native Android
library.

- `aether/` contains the Aether transport modules used by the JNI adapter.
- `quiche/` contains the `quiche`, `octets`, and `qlog` workspace members used
  by that transport layer.
- `REVISION` is a SHA-256 content fingerprint of the vendored files.

The snapshot contains no Git metadata. Unrelated applications, examples, test
certificates, fuzzing inputs, and tools are excluded. Original license and
copyright files remain alongside the corresponding source.
