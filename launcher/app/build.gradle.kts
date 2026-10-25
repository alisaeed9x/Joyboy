import java.util.Properties

// Trending settings live in local.properties (gitignored, see local.properties.example): tmdb.key, tmdb.region, tmdb.feed
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "me.prince.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "me.prince.launcher"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        buildConfigField("String", "TMDB_KEY", "\"${localProperties.getProperty("tmdb.key", "")}\"")
        buildConfigField("String", "TMDB_REGION", "\"${localProperties.getProperty("tmdb.region", "")}\"")
        buildConfigField("String", "TMDB_FEED", "\"${localProperties.getProperty("tmdb.feed", "all")}\"")
    }

    buildTypes {
        release {
            // Sideloaded only, so the debug key is fine; R8 makes Compose noticeably faster on low-end TV hardware
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true   // NewPipeExtractor uses newer java.util APIs
    }
    packaging { resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/DEPENDENCIES", "/META-INF/*.version") } }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.palette)
    // Player engine (VLC): plays nearly any format. Maven Central.
    implementation("org.videolan.android:libvlc-all:3.6.0")
    // YouTube without ads/login: the same extractor Subtitler uses (v0.26.5 via JitPack)
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
