# راهنمای بیلد

## پیش‌نیازها

- JDK 17
- Android SDK Platform 35
- Android Build Tools
- Android NDK r26b یا نسخه سازگار
- Rust 1.88 یا جدیدتر
- `cargo-ndk`

سورس‌های لازم موتور در پوشهٔ `engine` همین مخزن قرار دارند. پروژه از یک
دریافت مستقل ساخته می‌شود و به پوشه یا مخزن دیگری در کنار خود نیاز ندارد.

## ساخت کتابخانه بومی

```bash
export ANDROID_NDK_HOME=/path/to/android-ndk-r26b
cd native
cargo ndk -t arm64-v8a -P 24 -o ../app/src/main/jniLibs build --release
```

خروجی لازم:

```text
app/src/main/jniLibs/arm64-v8a/libaether_android.so
```

اگر `cargo-ndk` فایل‌های `libquiche.so` یا `libboringtun-*.so` را هم کپی کرد،
آن‌ها وابستگی زمان اجرای `libaether_android.so` نیستند و نباید داخل APK قرار
بگیرند.

## ساخت APK

در لینوکس یا macOS:

```bash
./gradlew assembleDebug
```

در ویندوز:

```powershell
.\gradlew.bat assembleDebug
```

خروجی:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## کنترل نهایی

```bash
./gradlew lintDebug
apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk
zipalign -c 4 app/build/outputs/apk/debug/app-debug.apk
```

نصب روی دستگاه ARM64:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## بیلد منتشرشده

نسخهٔ آمادهٔ نصب `0.3.0` پس از گذراندن کنترل‌های بالا در مسیر زیر قرار
می‌گیرد:

```text
releases/Koku-v0.3.0-arm64-v8a-debug.apk
```

یادداشت تغییرات، اطلاعات بسته و مقدار SHA-256 در
[`releases/Koku-v0.3.0.md`](releases/Koku-v0.3.0.md) ثبت می‌شود.

اطلاعات دقیق منشأ کد در [PROVENANCE.md](PROVENANCE.md) و مرز فنی JNI در
[NATIVE_ENGINE.md](NATIVE_ENGINE.md) ثبت شده است.
