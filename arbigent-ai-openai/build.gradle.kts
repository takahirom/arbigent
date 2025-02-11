plugins {
  id("org.jetbrains.kotlin.jvm") version "2.0.21"
  id("org.jetbrains.kotlin.plugin.serialization") version libs.versions.kotlin
}

kotlin {
  explicitApi()
  java.toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
}

dependencies {
  implementation(project(":arbigent-core"))

  // To expose requestBuilderModifier
  api(libs.ktor.client.core)
  // For Image Assertion
  api("io.github.takahirom.roborazzi:roborazzi-ai-openai:1.41.0")
  api("io.github.takahirom.roborazzi:roborazzi-core:1.41.0")
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.ktor.serialization.json)
  implementation(libs.ktor.client.cio)
  implementation(libs.ktor.client.logging)
  implementation(libs.ktor.client.contentnegotiation)
  implementation(libs.kotlinx.io.core)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.identity.jvm)

  testImplementation(libs.junit)
}