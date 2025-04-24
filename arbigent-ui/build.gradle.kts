import org.jetbrains.compose.ExperimentalComposeLibrary
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
  id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin
  id("org.jetbrains.compose")
  id("org.jetbrains.kotlin.plugin.compose")
  id("io.github.takahirom.roborazzi") version "1.44.0-alpha02"
  id("org.jetbrains.kotlin.plugin.serialization")
  id("com.palantir.git-version") version "0.15.0"
}

val gitVersion: groovy.lang.Closure<String> by extra
version = gitVersion()

kotlin {
  java.toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
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
  implementation("com.charleskorn.kaml:kaml:0.67.0")
  implementation("com.github.javakeyring:java-keyring:1.0.4")
  // kotlin-test
  testImplementation(kotlin("test"))
  @OptIn(ExperimentalComposeLibrary::class)
  testImplementation(compose.uiTest)
  testImplementation("io.github.takahirom.robospec:robospec:0.2.0")
  // roborazzi
  testImplementation("io.github.takahirom.roborazzi:roborazzi-compose-desktop:1.44.0-alpha03")
  implementation(project(":arbigent-core"))
  implementation(project(":arbigent-ai-openai"))
  implementation("io.github.takahirom.rin:rin:0.3.0")
}

compose.desktop {
  application {
    buildTypes.release.proguard{
      version.set("7.6.1")
      // It seems that Jewel does not work with Proguard
      isEnabled = false
    }
    mainClass = "io.github.takahirom.arbigent.ui.MainKt"

    nativeDistributions {
      targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
      macOS {
        iconFile.set(project.file("icons/icon.icns"))
      }
      windows {
        iconFile.set(project.file("icons/icon.ico"))
        perUserInstall = true
      }
      linux {
        iconFile.set(project.file("icons/icon.png"))
      }
      packageName = "Arbigent"
      packageVersion = "1.0.0"
    }
  }
}
