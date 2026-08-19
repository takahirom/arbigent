plugins {
  id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin
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
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.ktor.serialization.json)
  implementation(libs.ktor.client.okhttp)
  implementation(libs.ktor.client.logging)
  implementation(libs.ktor.client.contentnegotiation)
  implementation(libs.kotlinx.io.core)
  implementation(libs.kotlinx.serialization.json)
  implementation("com.github.mrmike:ok2curl:0.8.0")

  testImplementation(libs.junit)
}
