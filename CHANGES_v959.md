# v959 — إصلاح فشل بناء v958 (`MtBar.kt` مش شايف `androidx.media3.ui`)

- **السبب:** تشغيل v958 فشل في `:app:compileDebugKotlin` — `MtBar.kt` بيستخدم `androidx.media3.ui.TimeBar` / `PlayerTimeBar` (سطور 90 و373 و556 و567–583) و`:app` مفيهوش `media3-ui` على الـclasspath (`:mobile` معرّفه `stmobileImplementation` فمش بيتمرر). النتيجة: `Unresolved reference 'ui'` و`Cannot access 'TimeBar'` و`'onScrubStart' overrides nothing`.
- **الإصلاح (سطر واحد في `app/build.gradle.kts`):** `implementation("androidx.media3:media3-ui:1.10.1")` — نفس `media3Version` بتاع باقي مكتبات media3.
- **ما اتغيّرش:** أي كود تاني — v959 = v958 + الـdependency دي بس.
- **ما اتجرّبش:** مفيش Gradle هنا — البناء الفعلي على GitHub Actions.
