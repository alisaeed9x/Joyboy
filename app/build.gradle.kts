plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.vanilla.tvlauncher"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.vanilla.tvlauncher"   // مؤقت — D-005: بانتظار اسم الحزمة من المستخدم
        minSdk = 26          // مؤقت — يتأكد من إصدار Android على TCL (NFR-8)
        targetSdk = 34
        versionCode = 8; versionName = "0.6.0"
    }
    buildTypes { debug { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }   // NewPipeExtractor بيستخدم List.of وغيره (زي Subtitler)
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
    packaging { resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES", "/META-INF/*.version") } }   // NewPipeExtractor (زي Subtitler)
}
dependencies {
    // D-008/D-010: نفس نسخة Subtitler v475 (بتتبني على Actions مع Kotlin 2.4.0 / compileSdk 36 / AGP 8.9.1)
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")   // D-013: آخر حل (HLS) لو مفيش روابط مباشرة
    implementation("androidx.media3:media3-datasource:1.10.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")   // D-013: نفس نسخة Subtitler v475 (مبنية فعلًا هناك)
}
