plugins {
  id("org.jetbrains.kotlin.jvm") version libs.versions.kotlin
  id("org.jetbrains.kotlin.plugin.serialization") version libs.versions.kotlin
  id("com.javiersc.semver") version "0.8.0"
  alias(libs.plugins.buildconfig)
}

semver {
  isEnabled.set(true)
  tagPrefix.set("")
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
  buildConfigField("VERSION_NAME", version.toString())
  useKotlinOutput { internalVisibility = false }
}

dependencies {
  implementation(project(":arbigent-core-web-report"))
  api("dev.mobile:maestro-orchestra:1.39.1")
  api("dev.mobile:maestro-client:1.39.1")
  implementation("dev.mobile:dadb:1.2.9")
  api("dev.mobile:maestro-ios:1.39.1")
  api("dev.mobile:maestro-ios-driver:1.39.1")

  api(project(":arbigent-core-model"))
  api(project(":arbigent-mcp-client"))
  implementation("com.charleskorn.kaml:kaml:0.67.0")
  api("org.mobilenativefoundation.store:cache5:5.1.0-alpha05")
  api("com.mayakapps.kache:file-kache:2.1.1")

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
  implementation(project(":arbigent-core-model"))
  implementation("io.github.darkxanter:webp-imageio:0.3.3")

  implementation("co.touchlab:kermit:2.0.4")
  testImplementation(kotlin("test"))
  // coroutine test
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.5.2")
  // robospec
  testImplementation("io.github.takahirom.robospec:robospec:0.2.0")
}
