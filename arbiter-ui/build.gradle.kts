import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi") version "1.38.0"
}

dependencies {
    // Note, if you develop a library, you should use compose.desktop.common.
    // compose.desktop.currentOs should be used in launcher-sourceSet
    // (in a separate module for demo project and in testMain).
    // With compose.desktop.common you will also lose @Preview functionality
    implementation(compose.desktop.currentOs)
    // kotlin-test
    testImplementation(kotlin("test"))
    @OptIn(ExperimentalComposeLibrary::class)
    testImplementation(compose.uiTest)
    testImplementation("io.github.takahirom.robospec:robospec:0.2.0")
    // roborazzi
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose-desktop:1.38.0")
    implementation(project(":arbiter-core"))
    implementation(project(":arbiter-ai-openai"))
    implementation("io.github.takahirom.rin:rin:0.3.0")
}

compose.desktop {
    application {
        mainClass = "MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "arbiter-ui"
            packageVersion = "1.0.0"
        }
    }
}
