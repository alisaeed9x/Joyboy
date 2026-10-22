# Subtitler YT (Lite) 1.1 — APK واحد لكل بيلد

مبنية على Lite 1.0، من غير تغيير في الكود.

## تثبيت البيلد: APK واحد لكل بيلد
- `splits.abi` اتقفل (`isEnable = false`) في `app/build.gradle.kts` → `assembleDebug` بيطلع **APK واحد universal** (فيه arm64 + armeabi-v7a) بدل 3.
- `.github/workflows/build.yml` اتبسّط: `gradle assembleDebug` بس (مفيش release)، ورفع `app/build/outputs/apk/debug/*.apk`، واسم الـartifact بيتضمن اسم الفرع (`subtitler-main-universal-debug` / `subtitler-lite-universal-debug`).
- كل فرع بيتبني لوحده، وأي دفعة جديدة على نفس الفرع بتلغي البيلد القديم (`concurrency`).
