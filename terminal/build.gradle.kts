plugins { id("com.android.library") }
// (v394) مكتبة طرفية Termux (terminal-emulator + terminal-view) منسوخة كما هي من termux-app v0.118.0 (Apache-2.0 للمكتبتين)
android {
    namespace = "com.termux.view"
    compileSdk = 36
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild { cmake { arguments += listOf("-DCMAKE_BUILD_TYPE=Release") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("androidx.annotation:annotation:1.8.2") }
