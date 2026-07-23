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

// maestro-* jars extracted from the pinned Maestro release artifact (2.x is not on Maven Central).
// See gradle/maestro.gradle.kts. api() so downstream modules see the maestro types arbigent
// re-exposes (MaestroCommand, MaestroException, MaestroFlowParser, ...).
@Suppress("UNCHECKED_CAST")
val maestroJars = rootProject.extra["maestroJars"] as FileCollection

dependencies {
  implementation(project(":arbigent-core-web-report"))
  api(maestroJars)

  // Transitive dependencies of the maestro-* jars, aligned to the versions bundled in the
  // pinned Maestro CLI (maestro/lib/) since those jars carry no POM. Only what arbigent needs
  // to compile and run is declared; Gradle resolves the rest (netty, guava, jackson-core,
  // truffle, ...) transitively.
  // The mixed grpc versions below (grpc-api/stub 1.57.2, grpc-core/netty/okhttp/protobuf
  // 1.50.2) intentionally mirror what the Maestro release artifact itself bundles; do NOT bump them
  // to a single version or the runtime classpath diverges from the one Maestro was built against.
  api("dev.mobile:dadb:2.0.0")
  api("io.grpc:grpc-stub:1.57.2")
  implementation("io.grpc:grpc-kotlin-stub:1.4.1")
  implementation("io.grpc:grpc-netty:1.50.2")
  implementation("io.grpc:grpc-okhttp:1.50.2")
  implementation("io.grpc:grpc-protobuf:1.50.2")
  api("com.google.protobuf:protobuf-kotlin:3.21.9")
  implementation("com.michael-bull.kotlin-result:kotlin-result:2.0.1")
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
  implementation("com.squareup.okio:okio:3.16.2")
  implementation("com.github.romankh3:image-comparison:4.4.0")
  implementation("org.rauschig:jarchivelib:1.2.0")
  implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.1")
  implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:2.17.1")
  implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.1")
  implementation("net.dongliu:apk-parser:2.6.10")
  implementation("net.harawata:appdirs:1.2.1")
  implementation("com.google.code.gson:gson:2.11.0")
  implementation("commons-io:commons-io:2.16.1")
  implementation("net.datafaker:datafaker:2.5.3")
  implementation("io.micrometer:micrometer-core:1.13.4")
  implementation("org.apache.logging.log4j:log4j-api:2.25.3")
  implementation("org.apache.logging.log4j:log4j-core:2.25.3")
  implementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.25.3")
  implementation("org.apache.logging.log4j:log4j-layout-template-json:2.25.3")
  // GraalJS runtime that Maestro's Orchestra uses to evaluate JS in flows (runtime-only:
  // arbigent references no graal type directly).
  runtimeOnly("org.graalvm.polyglot:polyglot:24.2.0")
  runtimeOnly("org.graalvm.polyglot:js-community:24.2.0")
  // Selenium backs Maestro.web() (arbigent's Web device); runtime-only.
  runtimeOnly("org.seleniumhq.selenium:selenium-java:4.43.0")
  // maestro-web's CdpClient speaks CDP over ktor client websockets, but its ktor is 2.3.13
  // and conflicts with arbigent's ktor 3.x. That ktor is shaded into the maestro-web jar
  // itself (see gradle/maestro.gradle.kts), so no ktor 2.x runtime dep is declared here.
  // jcodec backs Maestro screen recording; runtime-only.
  runtimeOnly("org.jcodec:jcodec:0.2.5")
  runtimeOnly("org.jcodec:jcodec-javase:0.2.5")

  api(project(":arbigent-core-model"))
  api(project(":arbigent-mcp-client"))
  implementation("com.charleskorn.kaml:kaml:0.83.0")
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
