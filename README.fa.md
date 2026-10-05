# موتور اندروید Koku

نسخهٔ ۰.۴.۰ از موتور کامل **Aether 2.3.0** با commit
`6175b67df370ab856bcee07fe85b524031903956` استفاده می‌کند.

- MASQUE با HTTP/2 و HTTP/3، WireGuard، تونل WireGuard داخل MASQUE، gool کلاسیک و MASQUE داخل MASQUE.
- پنج روش اسکن، اتصال مجدد خودکار و استفادهٔ دوباره از آخرین دروازهٔ سالم پس از بررسی آن.
- Tor و Psiphon داخل تونل، بیرون تونل یا مستقل؛ helperهای اندرویدی Psiphon و Lyrebird داخل APK قرار دارند.
- قوانین مسیریابی دامنه، IP، CIDR و پورت؛ مسدودسازی یا عبور مستقیم، و پراکسی بالادستی.
- قطعه‌بندی TLS، تنظیمات ECH و QUIC، کنترل اثرانگشت TLS و آدرس ثبت‌نام WARP.
- Zero Trust با توکن یا ورود از داخل برنامه با کد ایمیل، و Gateway سازمان.

تنظیمات جدید در Settings قرار دارند. انتخاب پروتکل و روش اسکن ذخیره می‌شود.
VPN ترافیک IPv4 و IPv6 را از پل SOCKS عبور می‌دهد؛ قابلیت‌های DNS Storming،
انتخاب برنامه‌ها، لاگ، نمایش خروجی و به‌روزرسانی حفظ شده‌اند. DNS از پراکسی و TCP
عبور می‌کند. خروجی Tor و Psiphon برای TCP است؛ برای برنامه‌های نیازمند UDP از
خروجی WARP استفاده کنید.

محافظ NO-IR ابتدا خروجی WARP و سپس پراکسی نهایی را بررسی می‌کند. بررسی خروجی
نهایی هر ۶۰ ثانیه تکرار می‌شود؛ خروجی ایرانی یا نامشخص باعث توقف VPN می‌شود.

فهرست تطبیق قابلیت‌ها: [FEATURE_PARITY.md](FEATURE_PARITY.md).
دستورهای بیلد و انتشار: [BUILD_TOOLCHAIN.md](BUILD_TOOLCHAIN.md).
منشأ سورس و مجوزها: [PROVENANCE.md](PROVENANCE.md) و [NOTICE.md](NOTICE.md).

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-helpers.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build-release.ps1
```
