plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.vanilla.tvlauncher"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.vanilla.tvlauncher"   // مؤقت — D-005: بانتظار اسم الحزمة من المستخدم
        minSdk = 26          // مؤقت — يتأكد من إصدار Android على TCL (NFR-8)
        targetSdk = 34
        versionCode = 3; versionName = "0.2.1"
    }
    buildTypes { debug { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
}
dependencies {
    // D-008/D-010: نفس نسخة Subtitler v475 (بتتبني على Actions مع Kotlin 2.4.0 / compileSdk 36 / AGP 8.9.1)
    implementation("androidx.media3:media3-exoplayer:1.10.1")
}
