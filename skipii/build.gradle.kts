plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

// (v1080) Skipii (ad-free YouTube client, Compose) embedded as a library: Main's YouTube tab hosts its ComposeView (SkipiiEmbed.kt).
android {
    namespace = "com.decio.skipii"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        buildConfigField("String", "VERSION_NAME", "\"0.5.114\"")
        buildConfigField("int", "VERSION_CODE", "123")
        buildConfigField("String", "APPLICATION_ID", "\"com.tttt.joyboy\"")
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    val media3 = "1.10.1"
    implementation(composeBom)
    api("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")
    implementation("androidx.media3:media3-session:$media3")
    implementation("androidx.media3:media3-ui-compose:$media3")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.window:window:1.3.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
