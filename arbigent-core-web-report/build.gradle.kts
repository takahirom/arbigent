import com.android.build.gradle.internal.tasks.factory.dependsOn
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
  kotlin("multiplatform")
  kotlin("plugin.compose")
  id("org.jetbrains.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
  explicitApi()
  js(IR) {
    moduleName = "arbigentreport"
    browser {
    }
    binaries.executable()
    useEsModules()
  }
  // For making the JAR
  jvm()
  sourceSets {
    val commonMain by getting {
      dependencies {
        implementation(project(":arbigent-core-model"))
        implementation(compose.html.core)
        implementation(compose.runtime)
        implementation("com.charleskorn.kaml:kaml:0.67.0")
        implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")
      }
    }
  }
}

tasks.getByName<Jar>("jvmJar") {
  dependsOn("jsBrowserDistribution")
  from(layout.buildDirectory.dir("dist/js/productionExecutable/arbigent-core-web-report.js")) {
    into("arbigent-core-web-report-resources")
  }
}
