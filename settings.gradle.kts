pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Subtitler"

// (v1072) برنامج MeeTube اتشال خالص — اللي فاضل من مجلد meetube/ مكتبات بيانات يوتيوب بس (عميل API + واجهات الخدمة + أدوات مشتركة)، بتغذي صفحة الرئيسية والاشتراكات وتسجيل الدخول. مفيش واجهة MeeTube ولا مشغّل ولا إعدادات ولا لانشر.
val mt = File(settingsDir, "meetube")
gradle.extra["sharedModulesRoot"] = File(mt, "SharedModules")
gradle.extra["mediaServiceCoreRoot"] = File(mt, "MediaServiceCore")
gradle.extra["sharedModulesConstants"] = File(mt, "SharedModules/constants.gradle")

include(":app")
fun mod(name: String, dir: String) { include(name); project(name).projectDir = File(mt, dir) }
mod(":mediaserviceinterfaces", "MediaServiceCore/mediaserviceinterfaces")
mod(":youtubeapi", "MediaServiceCore/youtubeapi")
mod(":sharedutils", "SharedModules/sharedutils")
