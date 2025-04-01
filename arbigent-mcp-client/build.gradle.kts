plugins {
  id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin
  id("org.jetbrains.kotlin.plugin.serialization") version libs.versions.kotlin
  alias(libs.plugins.buildconfig)
}

kotlin {
  explicitApi()
  java.toolchain {
    languageVersion.set(JavaLanguageVersion.of(17))
  }
  sourceSets {
    all {
      languageSettings.optIn("io.github.takahirom.arbigent.ArbigentInternalApi")
    }
  }
}

buildConfig {
  packageName("io.github.takahirom.arbigent")
  useKotlinOutput { internalVisibility = false }
}

dependencies {
  // Kotlin Coroutines
  implementation(libs.kotlinx.coroutines.core)

  // Ktor Client Core and Engine
  implementation(libs.ktor.client.core)
  implementation(libs.ktor.client.cio)

  // Ktor Content Negotiation and Kotlinx Serialization
  implementation(libs.ktor.client.contentnegotiation)
  implementation(libs.ktor.serialization.json)

  // Kotlinx Serialization JSON runtime
  implementation(libs.kotlinx.serialization.json)

  // MCP Kotlin SDK
  implementation("io.modelcontextprotocol:kotlin-sdk:0.4.0")

  // Kotlinx IO (for stream conversion)
  implementation(libs.kotlinx.io.core)

  // Kermit Logging
  implementation("co.touchlab:kermit:2.0.4")

  // Project dependencies
  implementation(project(":arbigent-core-model"))

  // Test dependencies
  testImplementation(libs.junit)
  testImplementation("org.jetbrains.kotlin:kotlin-test:${libs.versions.kotlin.get()}")
  testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${libs.versions.kotlin.get()}")
}
