plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ali.ceilingir"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.ali.ceilingir"
        minSdk = 21
        targetSdk = 34
        versionCode = 713
        versionName = "7.13"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
