pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Subtitler"

// (v878) MeeTube (GPL-3.0) مدموج كمودولات جوه Joyboy — بدل PlayTube. المجلد: meetube/
val mt = File(settingsDir, "meetube")
gradle.extra["sharedModulesRoot"] = File(mt, "SharedModules")
gradle.extra["mediaServiceCoreRoot"] = File(mt, "MediaServiceCore")
gradle.extra["sharedModulesConstants"] = File(mt, "SharedModules/constants.gradle")

include(":app")
fun mod(name: String, dir: String) { include(name); project(name).projectDir = File(mt, dir) }
mod(":mobile", "mobile")
mod(":common", "common")
mod(":filepicker-lib", "filepicker-lib")
mod(":doubletapplayerview-media3", "doubletapplayerview-media3")
mod(":slidableactivity", "slidableactivity")
mod(":sabr-media3", "sabr-media3")
mod(":mediaserviceinterfaces", "MediaServiceCore/mediaserviceinterfaces")
mod(":youtubeapi", "MediaServiceCore/youtubeapi")
mod(":sharedutils", "SharedModules/sharedutils")
mod(":appupdatechecker2", "SharedModules/appupdatechecker2")
mod(":sharedtests", "SharedModules/sharedtests")
