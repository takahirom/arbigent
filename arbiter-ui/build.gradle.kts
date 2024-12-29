import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.github.takahirom.roborazzi") version "1.38.0"
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // Note, if you develop a library, you should use compose.desktop.common.
    // compose.desktop.currentOs should be used in launcher-sourceSet
    // (in a separate module for demo project and in testMain).
    // With compose.desktop.common you will also lose @Preview functionality
    implementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
    }
    implementation("org.jetbrains.jewel:jewel-int-ui-standalone-243:0.27.0")
    implementation("org.jetbrains.jewel:jewel-int-ui-decorated-window-243:0.27.0")
    implementation("com.jetbrains.intellij.platform:icons:243.22562.218")
//    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.charleskorn.kaml:kaml:0.67.0")
    implementation("com.github.javakeyring:java-keyring:1.0.4")
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
        mainClass = "com.github.takahirom.arbiter.ui.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "arbiter-ui"
            packageVersion = "1.0.0"
        }
    }
}
