# network-checker DNS Hunter

This directory records the upstream source used for Koku's DNS Hunter
integration.

- Project: https://github.com/mirarr-app/network-checker
- Upstream revision: `f2a259b3e53449c512183baf6805c0e99ed83500`
- Original components: `lib/core/services/dns_hunter_service.dart` and
  `lib/features/dns_hunter/data/dns_ranges.dart`
- Copyright: mirarr-app and network-checker contributors
- License: GNU General Public License, version 3.0 (`GPL-3.0`)

Koku is licensed under GNU AGPL-3.0-or-later. The Android implementation in
`app/src/main/java/io/github/lvl45t3r/koku/DnsHunter.kt` is an adaptation of
the upstream DNS-Hunter packet construction, reply validation, public-address
filtering, timeout, and resolver-selection method. It is distributed under
the same source-availability terms. The unmodified upstream GPL-3.0 license
is available at https://github.com/mirarr-app/network-checker/blob/f2a259b3e53449c512183baf6805c0e99ed83500/LICENSE.

The unmodified Iranian ISP range data is retained as
`dns_ranges.dart` and packaged at runtime as
`app/src/main/assets/network_checker_dns_ranges.dart`. These CIDRs are scan
targets, not a statement that every address is a DNS resolver.
