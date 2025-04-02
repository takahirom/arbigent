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
}
