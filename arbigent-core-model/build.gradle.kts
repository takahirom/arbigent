import java.util.Properties

plugins {
  kotlin("multiplatform")
  id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
  explicitApi()
  java.toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
  jvm()
  js {
    nodejs()
  }
  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.0")
        implementation("com.charleskorn.kaml:kaml:0.67.0")
        implementation("co.touchlab:kermit:2.0.4")
      }
    }
  }
}