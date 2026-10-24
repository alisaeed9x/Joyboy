import java.io.IOException
import java.net.URL

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// (v260) sherpa-onnx: مكتبة الـ AAR الرسمية (فيها كلاسات com.k2fsa.sherpa.onnx + libsherpa-onnx-jni.so + onnxruntime).
// كانت ناقصة من الـ dependencies فكان SherpaStt.kt بيفشل "Unresolved reference: k2fsa". بتتنزّل مرة واحدة في app/libs وقت البناء.
val sherpaVer = "1.12.39"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVer.aar")
if (!sherpaAar.isFile || sherpaAar.length() < 50_000_000L) {
    sherpaAar.parentFile.mkdirs()
    val part = File(sherpaAar.path + ".part")
    var err: Exception? = null
    for (attempt in 1..3) {
        try {
            URL("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVer/sherpa-onnx-$sherpaVer.aar")
                .openStream().use { ins -> part.outputStream().use { ins.copyTo(it) } }
            if (part.length() > 50_000_000L) { part.renameTo(sherpaAar); err = null; break }
            err = IOException("downloaded file too small: ${part.length()} bytes")
        } catch (e: Exception) { err = e }
    }
    if (!sherpaAar.isFile) throw GradleException("sherpa-onnx AAR download failed: ${err?.message}")
}
android {
    signingConfigs {
        getByName("debug") {
            storeFile = file("subtitler.keystore"); storePassword = "subtitler123"
            keyAlias = "subtitler"; keyPassword = "subtitler123"
        }
    }
    namespace = "com.tttt.subtitler"
    compileSdk = 36
    defaultConfig { applicationId = "com.tttt.subtitler"; minSdk = 26; targetSdk = 34; versionCode = 529; versionName = "5.29"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release"); abiFilters += listOf("arm64-v8a") } }   // (v253) whisper.cpp + (v307) substem: arm64 بس (armeabi-v7a من غير أوفلاين)   // (v181) معماريات الموبايل بس: بتصغّر مكتبة onnxruntime
    }
    buildTypes {
        release {
            // (v458) R8 + تصغير الموارد: الـ APK كان بيطلع من غير أي تصغير. القواعد محافظة جدًا
            // (proguard-rules.pro بيحفظ كل كلاسات التطبيق والمكتبات الانعكاسية/JNI) — جرّب الإصدار كويس قبل النشر.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug { isMinifyEnabled = false }
    }
    // (v460) تقسيم حسب المعمارية: الـ APK الواحد كان شايل مكتبات arm64 + armeabi-v7a مع بعض (~100MB).
    // دلوقتي كل معمارية ليها APK لوحده (اللي هتنزّله لجهازك هو اللي فيه arm64-v8a في اسمه — أصغر بكتير)،
    // واليونيفرسال لسه بيتبني كاحتياطي. armeabi-v7a من غير مكتبة Whisper الأوفلاين كالعادة (v253).
    splits {
        abi {
            isEnable = false   // (v474) APK واحد universal لكل بيلد — مفيش تقسيم ABI
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }   // (v327) NewPipeExtractor بيستخدم List.of وغيره
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }   // (v421) كوتلن 2.4.0 — نفس نسخة stdlib اللي litertlm بتجيبها (كان 1.9.24 فالبناء بيقع)
    packaging { resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES", "/META-INF/*.version") } }   // (v325) NewPipeExtractor
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-datasource:1.10.1")
    implementation("androidx.media3:media3-effect:1.10.1")
    implementation("androidx.media3:media3-transformer:1.10.1")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("androidx.core:core:1.13.1")   // (v310) FileProvider للفايل مانجر
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation(files("libs/sherpa-onnx-$sherpaVer.aar"))   // (v260)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")   // (v327)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")   // (v418) WebSocket لـ Edge TTS (دبلجة عربي)
    implementation("com.google.mlkit:translate:17.0.3")   // (v408) ML Kit: ترجمة أوفلاين (فصحى) — موديل ~30MB لكل لغة بيتحمّل مرة
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")   // (v325) يوتيوب الداخلي — نفس نسخة Flow؛ محتاج jitpack.io في settings (add_jitpack.sh)
}

// (v398) مسح أي ملف .kt قديم فاضل في الريبو ومش في kt_manifest.txt (قايمة ملفات الإصدار الحالي) قبل الكومبايل —
// بيمنع أخطاء «Unresolved reference» من ملفات اتشالت في إصدار جديد بس ما اتمسحتش من GitHub.
val pruneStaleKt = tasks.register("pruneStaleKt") {
    doLast {
        val mf = file("kt_manifest.txt")
        if (mf.isFile) {
            val want = mf.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val root = file("src/main/java")
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val rel = f.relativeTo(root).invariantSeparatorsPath
                if (rel !in want) { println("pruning stale source: " + rel); f.delete() }
            }
        }
    }
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(pruneStaleKt) }
