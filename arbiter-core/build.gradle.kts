plugins {
  id("org.jetbrains.kotlin.jvm") version "2.0.21"
  id("org.jetbrains.kotlin.plugin.serialization") version libs.versions.kotlin
}

kotlin {
  java.toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

dependencies {
  api("dev.mobile:maestro-orchestra:1.39.1")
  api("dev.mobile:maestro-client:1.39.1")
  api("dev.mobile:maestro-ios:1.39.1")
  api("dev.mobile:maestro-ios-driver:1.39.1")

  implementation("com.charleskorn.kaml:kaml:0.67.0")

  // To expose requestBuilderModifier
  api(libs.ktor.client.core)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.ktor.serialization.json)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.logging)
  implementation(libs.ktor.client.contentnegotiation)
  implementation(libs.kotlinx.io.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.identity.jvm)
}