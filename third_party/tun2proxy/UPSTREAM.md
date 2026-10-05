# tun2proxy integration

Source: https://github.com/tun2proxy/tun2proxy/tree/62109025df9b331ae9e5fca8e49f812eb6ce0747
Version: 0.8.4; license: MIT (LICENSE).

The Android adapter uses the library with default features disabled, creates an
async TUN device from the fd supplied by VpnService, and sends TCP/UDP sessions to
the selected local SOCKS5 endpoint. DNS is converted to TCP through that proxy.
The build script's Windows wintun copy is guarded by the target OS so Windows
hosts can cross-build Android. This is the only local build adjustment.
