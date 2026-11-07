plugins {
    kotlin("jvm") version "1.9.24"
    application
}
repositories { mavenCentral() }
dependencies {
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation("org.json:json:20240303")
}
kotlin { jvmToolchain(17) }
application { mainClass.set("com.tttt.joyboy.desk.MainKt") }
tasks.jar { archiveFileName.set("JoyboyDesk.jar"); manifest { attributes["Main-Class"] = "com.tttt.joyboy.desk.MainKt" } }
