pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Subtitler"

// (v878) MeeTube (GPL-3.0) مدموج كمودولات جوه Joyboy — المجلد: meetube/
// (v1148) اتشالت واجهة ميتيوب كلها (:mobile · :common · :filepicker-lib · :doubletapplayerview-media3 · :slidableactivity · :sabr-media3 · :appupdatechecker2) — كانت كلها مقفولة من المانيفست.
//   فاضل بس محرك يوتيوب: :youtubeapi · :mediaserviceinterfaces · :sharedutils (+ :sharedtests عشان testImplementation بتاعتهم).
val mt = File(settingsDir, "meetube")
gradle.extra["sharedModulesRoot"] = File(mt, "SharedModules")
gradle.extra["mediaServiceCoreRoot"] = File(mt, "MediaServiceCore")
gradle.extra["sharedModulesConstants"] = File(mt, "SharedModules/constants.gradle")

include(":app")
fun mod(name: String, dir: String) { include(name); project(name).projectDir = File(mt, dir) }
mod(":mediaserviceinterfaces", "MediaServiceCore/mediaserviceinterfaces")
mod(":youtubeapi", "MediaServiceCore/youtubeapi")
mod(":sharedutils", "SharedModules/sharedutils")
mod(":sharedtests", "SharedModules/sharedtests")
