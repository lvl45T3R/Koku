# بیلد تکرارپذیر Koku در ویندوز

این فایل مرجع قطعی بیلد release است. ابزارهای حجیم یک‌بار در `.tooling/` قرار می‌گیرند؛ این پوشه gitignored است ولی عمداً از دیسک پاک نمی‌شود. اسکریپت بیلد هیچ ابزار یا dependency را دانلود نمی‌کند و در صورت نبودن هر جزء fail-fast می‌شود.

## مبنای خروجی

- کد برنامه و نسخه از همین checkout و `app/build.gradle.kts` گرفته می‌شود.
- snapshot موتور از `engine/REVISION` خوانده و توسط `native/build.rs` داخل کتابخانه native ثبت می‌شود.
- dependencyهای Rust با `native/Cargo.lock` و dependencyهای Android با Gradle Wrapper و نسخه‌های pin‌شده در `build.gradle.kts` تعیین می‌شوند.
- API حداقل native برابر 24، `compileSdk` و `targetSdk` برابر 35 و Java target برابر 17 است.
- تنها فایل native لازم در APK، `libaether_android.so` است.

## toolchain تأییدشده در 2026-08-16

| جزء | نسخه دقیق | محل پایدار |
|---|---:|---|
| Windows | 11 10.0 amd64 | سیستم میزبان |
| JDK | Oracle 17.0.13+10 LTS | `C:\Program Files\Java\jdk-17` |
| Gradle Wrapper | 8.10.2 | داخل مخزن |
| Android Gradle Plugin | 8.7.3 | `build.gradle.kts` |
| Kotlin | 2.0.21 | `build.gradle.kts` |
| Android SDK | Platform 35 / Build Tools 37.0.0 | `%LOCALAPPDATA%\Android\Sdk` |
| Rust | rustc/cargo 1.97.1 | `%USERPROFILE%\.cargo\bin` |
| cargo-ndk | 4.1.2 | `%USERPROFILE%\.cargo\bin` |
| Android NDK | r26b / 26.1.10909125 | `.tooling\android-ndk-r26b` |
| CMake | 3.27.9 | `.tooling\cmake-3.27.9-windows-x86_64` |
| Ninja | 1.13.2 | `.tooling\ninja-1.13.2` |
| LLVM/libclang | 18.1.8 | `.tooling\llvm-18.1.8` |

NDK r26b با SHA-1 رسمی `17453C61A59E848CFFB8634F2C7B322417F1732E` و SHA-256 محلی `A478D43D4A45D0D345CDA6BE50D79642B92FB175868D9DC0DFC86181D80F691E` تأیید شده است. SHA-256 آرشیو CMake برابر `C14E8B5D1C7BE0BAF0E7936CE8B5A39C5EE3450B14D7E3B32435083EDDD9AFF7` و آرشیو Ninja برابر `07FC8261B42B20E71D1720B39068C2E14FFCEE6396B76FB7A795FB460B78DC65` است.
SHA-256 نصب‌کننده LLVM 18.1.8 نیز `94AF030060D88CC17E9F00EF1663EBDC1126B35E16BEBDFA1E807984B70ABD8F` است.

## بیلدهای بعدی؛ بدون دانلود

از ریشه مخزن اجرا شود:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-release.ps1
```

فقط ARM64:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-release.ps1 -Abis arm64-v8a
```

فقط بازسازی APK با کتابخانه‌های موجود:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build-release.ps1 -SkipNative
```

اسکریپت workaround لازم ویندوز برای `boring-sys` را نیز اعمال می‌کند: به‌جای بازنویسی مسیر compiler توسط `cargo-ndk`، target/linker/clang دقیق NDK به Cargo داده می‌شود و `.so` به `app/src/main/jniLibs/<ABI>/` کپی می‌شود.

## امضای release

هیچ secret در مخزن نوشته نمی‌شود. قبل از Gradle این چهار متغیر باید در همان process حاضر باشند:

```text
KOKU_SIGNING_STORE_FILE
KOKU_SIGNING_STORE_PASSWORD
KOKU_SIGNING_KEY_ALIAS
KOKU_SIGNING_KEY_PASSWORD
```

وجودشان بدون چاپ مقدار:

```powershell
'KOKU_SIGNING_STORE_FILE','KOKU_SIGNING_STORE_PASSWORD','KOKU_SIGNING_KEY_ALIAS','KOKU_SIGNING_KEY_PASSWORD' | ForEach-Object { "$_=" + $(if([Environment]::GetEnvironmentVariable($_)){'present'}else{'missing'}) }
```

## کنترل نهایی

```powershell
.\gradlew.bat lintRelease --no-daemon
```

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\apksigner.bat" verify --verbose --print-certs .\app\build\outputs\apk\release\app-release.apk
```

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\37.0.0\zipalign.exe" -c -P 16 -v 4 .\app\build\outputs\apk\release\app-release.apk
```

برای اثبات اینکه خروجی از همین source گرفته شده، قبل از انتشار `git status --short`، مقدار `engine/REVISION`، نسخه APK، ABIهای داخل APK، certificate و SHA-256 هر asset ثبت می‌شود.

## bootstrap فقط در صورت حذف `.tooling`

منابع یک‌باره عبارت‌اند از Android NDK r26b، CMake 3.27.9، Ninja 1.13.2 و LLVM 18.1.8. URLهای pin‌شده در زیر هستند؛ بعد از بازیابی، checksumهای بالا/فایل release باید بررسی و آرشیوها نگه داشته شوند.

```text
https://redirector.gvt1.com/edgedl/android/repository/android-ndk-r26b-windows.zip
https://github.com/Kitware/CMake/releases/download/v3.27.9/cmake-3.27.9-windows-x86_64.zip
https://github.com/ninja-build/ninja/releases/download/v1.13.2/ninja-win.zip
https://github.com/llvm/llvm-project/releases/download/llvmorg-18.1.8/LLVM-18.1.8-win64.exe
```

Rust targetهای لازم یک‌بار نصب می‌شوند:

```powershell
rustup target add aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android
```

نکته: `clean` کردن Gradle یا Cargo لازم نیست و cacheهای سالم را بی‌دلیل حذف می‌کند. فقط وقتی ورودی native یا ABI تغییر کرده است native را دوباره بسازید.

## Runbook قطعی انتشار روی GitHub

مخزن مقصد Koku است؛ `origin` این checkout ممکن است هنوز به `lvl45T3R/Aether` اشاره کند. برای جلوگیری از انتشار روی مخزن اشتباه، remote مستقل زیر باید استفاده شود:

```powershell
if(-not (git remote | Select-String -SimpleMatch 'koku')){ git remote add koku https://github.com/lvl45T3R/Koku.git }; git fetch --depth=1 koku main
```

ورود GitHub باید با حساب `lvl45T3R` و در همان Windows context انجام شود که فرمان‌های publish را اجرا می‌کند:

```powershell
gh auth login -h github.com --git-protocol https --web; gh api user --jq .login
```

خروجی فرمان دوم باید دقیقاً `lvl45T3R` باشد. پیام «Authentication complete» به‌تنهایی کافی نیست. در محیط محدود Codex، Windows Keyring فقط در shell دارای دسترسی elevated دیده می‌شود؛ بنابراین `gh auth status`، `gh api`، `git push` و `gh release create` باید در همان محیط elevated اجرا شوند. توکن هرگز چاپ یا داخل فایل ذخیره نشود.

ترتیب انتشار:

1. `versionCode` و `versionName` را در `app/build.gradle.kts` افزایش دهید و ورودی جدید `CHANGELOG.md` و `releases/Koku-vX.Y.Z.md` را بسازید.
2. چهار `.so` موجود در `app/src/main/jniLibs` باید از آخرین source native باشند. اگر native تغییر کرده، `scripts/build-release.ps1 -SkipApk` را اجرا کنید؛ اگر فقط version/docs تغییر کرده، rebuild native لازم نیست.
3. سه APK امضاشده را جداگانه بسازید و بلافاصله کپی کنید:

```powershell
$kokuVersion='X.Y.Z'; .\gradlew.bat assembleRelease -PkokuAbis=arm64-v8a --no-daemon; Copy-Item app\build\outputs\apk\release\app-release.apk releases\Koku-v$kokuVersion-arm64-v8a.apk -Force; .\gradlew.bat assembleRelease -PkokuAbis=armeabi-v7a --no-daemon; Copy-Item app\build\outputs\apk\release\app-release.apk releases\Koku-v$kokuVersion-armeabi-v7a.apk -Force; .\gradlew.bat assembleRelease --no-daemon; Copy-Item app\build\outputs\apk\release\app-release.apk releases\Koku-v$kokuVersion-universal.apk -Force
```

4. `lintRelease`، `apksigner verify`، `zipalign -c -P 16 4`، نسخه با `aapt2 dump badging`، ABIهای داخل ZIP، SHA-256 و تطابق hash کتابخانه‌های native داخل APK با `jniLibs` را بررسی و در release note ثبت کنید.
5. فقط فایل‌های همین release را stage کنید، commit بسازید، تاریخچه `koku/main` را ادغام کنید و source را به `koku/main` push کنید. در worktree مخلوط از `git add -A` استفاده نشود.
6. tag جدید را overwrite نکنید. release را از commit منتشرشده main بسازید:

```powershell
$kokuVersion='X.Y.Z'; gh release create "v$kokuVersion" "releases\Koku-v$kokuVersion-arm64-v8a.apk" "releases\Koku-v$kokuVersion-armeabi-v7a.apk" "releases\Koku-v$kokuVersion-universal.apk" --repo lvl45T3R/Koku --target main --title "Koku v$kokuVersion" --notes-file "releases\Koku-v$kokuVersion.md"
```

7. با `gh release view vX.Y.Z --repo lvl45T3R/Koku --json url,assets,body` نام، digest و اندازه assetهای آنلاین را با فایل‌های محلی مقایسه کنید.

## خطاهای دیده‌شده و راه‌حل ثابت

| علامت | علت واقعی | اقدام ثابت |
|---|---|---|
| `gh auth status` یا API خطای 401 می‌دهد ولی login موفق گزارش شده | shell محدود به Windows Keyring دسترسی ندارد یا credential قدیمی فعال است | تمام auth/publish را در یک shell elevated اجرا کنید و حتماً `gh api user --jq .login` را ملاک قرار دهید. |
| push یا tag به پروژه اشتباه می‌رود | `origin` این checkout به Aether اشاره می‌کند | همیشه remote صریح `koku` و `--repo lvl45T3R/Koku` را استفاده کنید. |
| NDK نصب‌شده SDK ناقص است | پوشه `26.1.10909125` فقط `.installer` داشت | NDK r26b تأییدشده داخل `.tooling/android-ndk-r26b` را نگه دارید. |
| URL مستقیم NDK خطای 404 می‌دهد | مسیر شبکه مستقیم Google در این میزبان قابل اتکا نبود | URL pin‌شده `redirector.gvt1.com` و checksum ثبت‌شده بالا را استفاده کنید. |
| `boring-sys` در CMake با `Invalid character escape '\x'` می‌شکند | `cargo-ndk` مسیر compiler ویندوز را با backslash دوباره وارد cache می‌کند | فقط `scripts/build-release.ps1`؛ این اسکریپت target/linker را مستقیم به Cargo می‌دهد. |
| Ninja یا `libclang.dll` پیدا نمی‌شود | BoringSSL/bindgen به ابزارهای host نیاز دارند | CMake، Ninja و LLVM pin‌شده `.tooling` را از PATH حذف نکنید. |
| دیسک هنگام host Rust test پر می‌شود | target جداگانه host نزدیک 2GB فضا می‌گیرد و برای APK لازم نیست | فقط cache ناموفق `engine/aether/target` را پاک کنید؛ `native/target` و `.tooling` را نگه دارید. |
| APK ساخته شده ولی ABI اشتباه دارد | خروجی `app-release.apk` بین variantها overwrite می‌شود | بعد از هر variant فوراً کپی و سپس `tar -tf`/ABI contents را بررسی کنید. |
| release موجود با source جدید یکی نیست | assetهای tag قدیمی rebuild یا جایگزین شده‌اند | نسخه را یک پله بالا ببرید؛ tag موجود را بازنویسی نکنید و digest آنلاین را تطبیق دهید. |
| بیلد موفق است ولی رفتار واقعی VPN ثابت نشده | دستگاه یا emulator به ADB وصل نیست | این محدودیت را در release note بنویسید و اولین تست device را جداگانه ثبت کنید. |
