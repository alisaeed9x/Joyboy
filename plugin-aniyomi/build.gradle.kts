plugins { id("com.android.application") }

// (v1257) بلاجن Aniyomi: APK منفصل بيتبني وبيتنشر لوحده (مش جزء من الـAPK الأساسي). موقّع بنفس keystore بتاع Joyboy.
android {
    namespace = "com.tttt.joyboy.plugin.aniyomi"
    compileSdk = 37
    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("app/subtitler.keystore"); storePassword = "subtitler123"
            keyAlias = "subtitler"; keyPassword = "subtitler123"
        }
    }
    defaultConfig {
        applicationId = "com.tttt.joyboy.plugin.aniyomi"
        minSdk = 26; targetSdk = 34
        versionCode = (System.getenv("JB_PLUGIN_VC")?.toIntOrNull() ?: (System.currentTimeMillis() / 60000L).toInt())
        versionName = System.getenv("JB_PLUGIN_VN") ?: "1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { debug { isMinifyEnabled = false } }
}
