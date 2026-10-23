pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral()
        maven { url = uri("https://jitpack.io") } } }   // NewPipeExtractor (D-013) — نفس إعداد Subtitler
rootProject.name = "VanillaTvLauncher"
include(":app")
