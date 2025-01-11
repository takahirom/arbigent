import java.util.Properties

plugins {
  kotlin("multiplatform")
  id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
  jvm()
  js {
    nodejs()
  }
  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.0")
        implementation("com.charleskorn.kaml:kaml:0.67.0")
      }
    }
  }
}