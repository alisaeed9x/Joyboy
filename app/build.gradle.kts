import java.util.zip.ZipFile
plugins { id("com.android.application") }   // (v878) AGP 9.2: كوتلن مدمج؛ ksp/hilt اتشالوا مع PlayTube

// (v569) sherpa-onnx و ML Kit اتشالوا من الـ APK (كانوا بيزوّدوه ~100 ميجا) — التفريغ على الجهاز Whisper بس.
// (v820) اسم النسخة (اللي بيظهر في تحديث التلفزيون والموبايل) كان ثابت "7.99" من v799 فكان بيعرض 799 لكل النسخ بعدها.
// دلوقتي بيتحسب لوحده من أحدث CHANGES_vNNN.md في جذر المشروع (820 ← "8.20")؛ لو مالقاش ملفات بيرجع للاحتياطي.
val joyVerNum: Int = rootProject.projectDir.listFiles()?.mapNotNull { f -> Regex("CHANGES_v(\\d+)\\.md").matchEntire(f.name)?.groupValues?.get(1)?.toIntOrNull() }?.maxOrNull() ?: 820
val joyVerName: String = "${joyVerNum / 100}.${(joyVerNum % 100).toString().padStart(2, '0')}"
// (v1027) حجم التحميل: المعماريات من gradle.properties → joyAbi (مفصولة بفاصلة).
//   arm64-v8a بس (الافتراضي) = كل موبايلات ومعظم صناديق التلفزيون الحديثة، وبيشيل نص المكتبات الـnative تقريبًا.
//   لو جهاز 32-bit (بوكس تلفزيون قديم): joyAbi=arm64-v8a,armeabi-v7a أو joyAbi=armeabi-v7a
val joyMinify: Boolean = ((project.findProperty("joyMinify") as String?) ?: "true") != "false"   // (v1028)
val joyAbis: List<String> = ((project.findProperty("joyAbi") as String?) ?: "arm64-v8a,armeabi-v7a").split(",").map { it.trim() }.filter { it.isNotEmpty() }
android {
    signingConfigs {
        getByName("debug") {
            storeFile = file("subtitler.keystore"); storePassword = "subtitler123"
            keyAlias = "subtitler"; keyPassword = "subtitler123"
        }
    }
    namespace = "com.tttt.subtitler"
    compileSdk = 37   // (v869) 36→37: مكتبات PlayTube (core 1.19 / compose 2026) محتاجاها
    defaultConfig { applicationId = "com.tttt.joyboy"; minSdk = 26; targetSdk = 34; versionCode = (System.getenv("JB_VERSION_CODE")?.toIntOrNull() ?: (System.currentTimeMillis() / 60000L).toInt()); versionName = joyVerName
        // (v656) versionCode = وقت البناء بالدقايق: أي بيلد (حتى من سورس قديم) كوده أعلى من اللي قبله ← أندرويد يقبل التثبيت فوق الأحدث من غير حذف ولا فقد بيانات (نفس الـkeystore)
        ndk { abiFilters += joyAbis }   // (v1027) المعماريات من joyAbi في gradle.properties (الافتراضي arm64 بس = أصغر تحميل)
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release") + (if (System.getenv("JOY_STQ") == "1" || project.findProperty("joyStq") == "1") listOf("-DJOY_STQ=ON") else emptyList()); abiFilters += joyAbis } }   // (v253) whisper.cpp + (v307) substem: arm64 بس (armeabi-v7a من غير أوفلاين)   // (v181) معماريات الموبايل بس: بتصغّر مكتبة onnxruntime
    }
    buildTypes {
        release {
            // (v458) R8 + تصغير الموارد: الـ APK كان بيطلع من غير أي تصغير. القواعد محافظة جدًا
            // (proguard-rules.pro بيحفظ كل كلاسات التطبيق والمكتبات الانعكاسية/JNI) — جرّب الإصدار كويس قبل النشر.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // (v1028) الـCI بيبني assembleDebug (مش release) — وdebug كان من غير R8/تصغير موارد خالص، وده سبب إن الـAPK 60MB.
        // دلوقتي debug بيتصغّر زي release (نفس القواعد). لو البيلد/التشغيل باظ بسبب R8: joyMinify=false في gradle.properties.
        debug { isMinifyEnabled = joyMinify; isShrinkResources = joyMinify; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") }
    }
    // (v460) تقسيم حسب المعمارية: الـ APK الواحد كان شايل مكتبات arm64 + armeabi-v7a مع بعض (~100MB).
    // دلوقتي كل معمارية ليها APK لوحده (اللي هتنزّله لجهازك هو اللي فيه arm64-v8a في اسمه — أصغر بكتير)،
    // واليونيفرسال لسه بيتبني كاحتياطي. armeabi-v7a من غير مكتبة Whisper الأوفلاين كالعادة (v253).
    // (v878) splits (معطّل أصلًا من v474) اتشال — APK واحد universal
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17; isCoreLibraryDesugaringEnabled = true }   // (v327) NewPipeExtractor بيستخدم List.of وغيره
    // (v878) jvmTarget 17 بييجي تلقائي من compileOptions (كوتلن مدمج في AGP 9)
    testOptions { unitTests { isReturnDefaultValues = true } }   // (v589 T4) استدعاءات android.* في اختبارات JVM ترجّع قيم افتراضية بدل ما ترمي
    // (v1027) لغات المكتبات (material/appcompat/media3/…): كل واحدة جايبة ~80 لغة؛ واجهة التطبيق عربي + إنجليزي بس
    androidResources { localeFilters += listOf("ar", "en") }
    packaging { jniLibs { useLegacyPackaging = true; if ("armeabi-v7a" !in joyAbis) excludes += "**/armeabi-v7a/**"; if ("arm64-v8a" !in joyAbis) excludes += "**/arm64-v8a/**"; excludes += listOf("**/x86/**", "**/x86_64/**") }; resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES", "/META-INF/*.version") } }   // (v325) NewPipeExtractor
}
dependencies {
    implementation(project(":skipii"))   // (v1080) Skipii = تبويب يوتيوب على الموبايل
    implementation(project(":mediaserviceinterfaces"))   // (v885)
    implementation(project(":sharedutils"))   // (v1074) رجّعتها: youtubeapi/mediaserviceinterfaces معرّفينها implementation فمش بتتسرّب لـ:app — وJoyApp/MtYt/YtMtSignIn بيستوردوا GlobalPreferences مباشرة
    implementation("androidx.webkit:webkit:1.16.0")   // (v977) VidHook: addDocumentStartJavaScript + addWebMessageListener (نفس webkitVersion في meetube/constants.gradle)
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.10.1")
    implementation("androidx.media3:media3-ui:1.10.1")   // (v959) MtBar.kt بيستخدم TimeBar/PlayerTimeBar؛ :mobile معرّفها stmobileImplementation فمش بتتمرر لـ:app (نفس media3Version في constants.gradle)
    implementation("androidx.media3:media3-datasource:1.10.1")
    implementation("androidx.media3:media3-database:1.10.1")   // (v564) كاش فيديو الموبايل على التلفزيون
    implementation("androidx.media3:media3-effect:1.10.1")
    implementation("androidx.media3:media3-transformer:1.10.1")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")   // (v598) ماسح QR داخل التطبيق (اقتران التلفزيون + دخول يوتيوب)
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")   // (v834) يثبّت الـBaseline Profile حتى على التثبيت اليدوي (من غير Play)
    implementation("androidx.core:core:1.13.1")   // (v310) FileProvider للفايل مانجر
    implementation("androidx.recyclerview:recyclerview:1.3.2")   // (v772) لانشر الموبايل: صفحات الأيقونات + الدرج
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("com.google.android.material:material:1.14.0")   // (v909) GlassSwitch بيورّث SwitchMaterial؛ :mobile معرّفها implementation فكان :app مش شايف الـsupertype → "Type mismatch CompoundButton" (نفس materialVersion في constants.gradle)   // حركات الـspring
    implementation("com.github.bumptech.glide:glide:5.0.9")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")   // (v327)
    implementation("com.squareup.okhttp3:okhttp:5.4.0")   // (v869) 4.12→5.4 (نفس نسخة PlayTube)   // (v418) WebSocket لـ Edge TTS (دبلجة عربي)
    testImplementation("junit:junit:4.13.2")            // (v589 T4)
    testImplementation("org.json:json:20240303")        // org.json الحقيقي بدل stub بتاع android.jar (اللي بيرمي "not mocked")
    implementation(project(":youtubeapi")) { exclude(group = "com.grack", module = "nanojson") }   // (v889) MtYt بيجيب روابط الفيديو/الصوت من MediaServiceCore بتاع MeeTube (بدل NewPipeExtractor اللي اتشال)   // (v1074) استثناء nanojson بتاع youtubeapi: NewPipeExtractor بيجيب fork بنفس الكلاسات (com.github.TeamNewPipe:nanojson) — الاتنين مع بعض كانوا بيوقّعوا checkDebugDuplicateClasses
    implementation("io.reactivex.rxjava3:rxjava:3.1.12")   // (v1072) تسجيل دخول يوتيوب بالكود (YtMtSignIn): signInObserve بيرجّع Observable — نفس نسخة مكتبات youtubeapi (implementation مش بتتسرّب لـ:app)
    implementation("io.reactivex.rxjava3:rxandroid:3.0.2")   // (v1072) نفس السبب — AndroidSchedulers.mainThread
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")   // (v1072) يوتيوب الداخلي رجع — YtNp/YtProUi المستعادة؛ محتاج jitpack.io في settings
}

// (v1027) تقرير حجم الـAPK في لوج البناء (بعد assembleRelease): الحجم المضغوط لكل فئة (dex / res / assets / lib لكل معمارية) وأكبر 15 ملف.
// عشان نعرف بالأرقام إيه اللي بياخد المساحة قبل أي تصغير تاني.
tasks.matching { it.name == "assembleRelease" || it.name == "assembleDebug" }.configureEach {
    doLast {
        try {
            fileTree(layout.buildDirectory.dir("outputs/apk")).matching { include("**/*.apk") }.forEach { apk ->
                val z = ZipFile(apk)
                val es = z.entries().toList().sortedByDescending { it.compressedSize }
                println("== APK size report: " + apk.name + "  " + String.format("%.1f", apk.length() / 1048576.0) + " MB")
                es.groupBy { e ->
                    when {
                        e.name.startsWith("lib/") -> "lib/" + e.name.split("/").getOrElse(1) { "" }
                        e.name.startsWith("assets/") -> "assets"
                        e.name.startsWith("res/") -> "res"
                        e.name.endsWith(".dex") -> "dex"
                        else -> "other"
                    }
                }.forEach { (k, v) -> println(String.format("   %-18s %7.2f MB", k, v.sumOf { it.compressedSize } / 1048576.0)) }
                es.take(15).forEach { e -> println(String.format("   %7.2f MB  %s", e.compressedSize / 1048576.0, e.name)) }
                z.close()
            }
        } catch (t: Throwable) { println("APK size report failed: " + t) }
    }
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

// (v589 T4) اختبارات المنطق محتاجة مسار assets (prompts/fonts) — بتتقرا من متغير بيئة
tasks.withType(Test::class.java).configureEach {
    environment("SUBTITLER_ASSETS", "$projectDir/src/main/assets")
    testLogging { events("failed"); showStandardStreams = true }
}
