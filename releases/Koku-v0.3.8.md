# Koku v0.3.8

## DNS Storming

DNS Storming replaces the old on/off DNS Hunter switch with four explicit
modes:

- **Default** — Cloudflare `1.1.1.1`.
- **Public Hunter** — tests public fallback resolvers after a failed default
  validation.
- **Iranian Hunter** — samples up to 128 addresses from the bundled Iranian
  ISP CIDR data and uses only a resolver with a validated DNS response.
- **Custom DNS** — tests one or more manually entered IPv4 resolver addresses
  and selects the fastest valid response.

The Iranian range data and DNS Hunter method are derived from
[mirarr-app/network-checker](https://github.com/mirarr-app/network-checker)
at revision `f2a259b3e53449c512183baf6805c0e99ed83500`, under GPL-3.0. The
unmodified source data, GPL text, attribution, and Android adaptation are
included in the release source.

## Important limitation

DNS Storming changes hostname resolution inside the VPN. It cannot make a
literal blocked IP reachable and does not replace Koku's WARP gateway scan.

## Verification

`assembleRelease` completed successfully using the pinned local toolchain.

| APK | SHA-256 | Bytes |
| --- | --- | ---: |
| `Koku-v0.3.8-arm64-v8a.apk` | `edef9c3376db0ce6b4c35875f527852afd033d2bc0eefd6b8288299abdccdfeb` | 16,956,002 |
| `Koku-v0.3.8-armeabi-v7a.apk` | `003c876044a48b0e738b74cc83b980a045ff416e8905c3b08f732980c79f9d42` | 14,413,642 |
| `Koku-v0.3.8-universal.apk` | `99afe6acac17499f9d9dbe0ac6c31696cefb67e9e598c24649c22f952bccba77` | 38,403,812 |
