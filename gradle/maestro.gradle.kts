import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import java.io.File
import java.security.MessageDigest

// Shadow (gradleup fork) provides the ShadowJar task type used below to relocate ktor
// inside maestro-web. It works on arbitrary jar/config inputs, so no project sources are shaded.
buildscript {
  repositories {
    mavenCentral()
    gradlePluginPortal()
  }
  dependencies {
    classpath("com.gradleup.shadow:shadow-gradle-plugin:8.3.6")
  }
}

// Single source of truth for the pinned Maestro release consumed by arbigent.
//
// Maestro stopped publishing its `dev.mobile:maestro-*` artifacts to Maven Central at
// 1.40.0; 2.x is distributed only as jars bundled inside the official CLI zip. We pin
// that zip by version + sha256, download and verify it once, extract the maestro-* jars
// arbigent needs, and expose them as file dependencies (see arbigent-core/build.gradle.kts).
// The maestro jars' own transitive dependencies (grpc, jackson, okhttp, dadb, ...) are on
// Maven Central and are declared explicitly by the consuming modules.
val maestroVersion = "2.7.0"
val maestroZipSha256 = "a4ccab6b604617e7aef6db4f885666056eabe5cfa32befaa3bc994041b8fcbb5"
val maestroZipUrl =
  "https://github.com/mobile-dev-inc/Maestro/releases/download/cli-$maestroVersion/maestro.zip"

// maestro-* jars we consume; none are published to Maven Central for 2.x. Paths are as they
// appear inside the zip under maestro/lib/.
val maestroJarEntries = listOf(
  "maestro/lib/maestro-orchestra.jar",
  "maestro/lib/maestro-orchestra-models.jar",
  "maestro/lib/maestro-client.jar",
  "maestro/lib/maestro-ios.jar",
  "maestro/lib/maestro-ios-driver.jar",
  "maestro/lib/maestro-utils.jar",
  "maestro/lib/maestro-ai.jar",
  "maestro/lib/maestro-web.jar",
)

// Cache under the Gradle user home so the download survives `clean` and is shared across
// builds and modules. Keyed by version so bumping the pin naturally invalidates the cache.
val maestroCacheDir: File = gradle.gradleUserHomeDir.resolve("caches/arbigent-maestro/$maestroVersion")
val maestroZipFile: File = maestroCacheDir.resolve("maestro.zip")
val maestroJarsDir: File = maestroCacheDir.resolve("jars")
// Shaded maestro-web lives outside maestroJarsDir so the maestroJars fileTree doesn't pick up
// the plain jar; only the shaded one reaches the classpath.
val maestroShadedDir: File = maestroCacheDir.resolve("shaded")

fun sha256(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().use { input ->
    val buffer = ByteArray(1 shl 16)
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      digest.update(buffer, 0, read)
    }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
}

val downloadMaestroZip = tasks.register("downloadMaestroZip") {
  description = "Download the pinned Maestro CLI release zip and verify its sha256 checksum."
  group = "maestro"
  outputs.file(maestroZipFile)
  outputs.upToDateWhen { maestroZipFile.exists() && sha256(maestroZipFile) == maestroZipSha256 }
  doLast {
    maestroCacheDir.mkdirs()
    if (maestroZipFile.exists() && sha256(maestroZipFile) == maestroZipSha256) return@doLast
    logger.lifecycle("Downloading Maestro $maestroVersion from $maestroZipUrl")
    val tmp = File.createTempFile("maestro", ".zip", maestroCacheDir)
    try {
      uri(maestroZipUrl).toURL().openStream().use { input ->
        tmp.outputStream().use { output -> input.copyTo(output) }
      }
      val actual = sha256(tmp)
      check(actual == maestroZipSha256) {
        "Maestro zip checksum mismatch for $maestroZipUrl: expected $maestroZipSha256 but got $actual"
      }
      // Publish atomically: rename is atomic within a filesystem, so a concurrent build never
      // observes a half-written zip the way an incremental copyTo could expose. tmp lives in
      // maestroCacheDir alongside the target, so rename succeeds here; fall back to copy+delete
      // only if it fails (e.g. across filesystems).
      if (!tmp.renameTo(maestroZipFile)) {
        tmp.copyTo(maestroZipFile, overwrite = true)
      }
    } finally {
      tmp.delete()
    }
  }
}

// Extracts the maestro-* jars into a flat directory. A later PR adding real-device support
// can extract the zip's driver-iphoneos/ products here too by registering a sibling task
// against the same downloaded zip.
val extractMaestroJars = tasks.register<Copy>("extractMaestroJars") {
  description = "Extract the maestro-* jars arbigent depends on from the pinned CLI zip."
  group = "maestro"
  dependsOn(downloadMaestroZip)
  from(zipTree(maestroZipFile)) {
    include(maestroJarEntries)
    eachFile { path = name } // flatten maestro/lib/<jar> -> <jar>
  }
  includeEmptyDirs = false
  into(maestroJarsDir)
}

// maestro-web.jar's CdpClient is compiled against ktor 2.3.13, but arbigent-mcp-client pulls
// the MCP kotlin-sdk which requires ktor 3.x, so Gradle bumps ktor to 3.x on the shared
// classpath. ktor 3 dropped io.ktor.client.plugins.contentnegotiation.ContentNegotiation (and
// more), so maestro-web hits NoClassDefFoundError at runtime. maestro-client's CdpWebDriver
// touches no ktor type across the boundary (it only calls maestro.web.* ), so relocating ktor
// *inside* maestro-web is safe: we bundle the matching ktor 2.3.13 classes under a private
// package and rewrite maestro-web's references to point at them, leaving the rest of arbigent
// on ktor 3.x. Nothing else in the maestro jars references ktor except maestro-ai, which
// arbigent never loads.
val maestroWebKtor2: Configuration = configurations.detachedConfiguration(
  dependencies.create("io.ktor:ktor-client-core:2.3.13"),
  dependencies.create("io.ktor:ktor-client-cio:2.3.13"),
  dependencies.create("io.ktor:ktor-client-content-negotiation:2.3.13"),
  dependencies.create("io.ktor:ktor-client-websockets:2.3.13"),
  dependencies.create("io.ktor:ktor-serialization-kotlinx-json:2.3.13"),
)

// Only the io.ktor artifacts from the resolved graph get shaded in; kotlinx/kotlin/slf4j stay
// as ordinary (unrelocated) classpath deps supplied by arbigent so we don't ship duplicates.
val maestroWebKtor2Jars: FileCollection = maestroWebKtor2.incoming.artifactView {
  componentFilter { it is ModuleComponentIdentifier && it.group == "io.ktor" }
}.files

val shadeMaestroWeb = tasks.register<ShadowJar>("shadeMaestroWeb") {
  description = "Relocate ktor 2.3.13 inside maestro-web to avoid clashing with arbigent's ktor 3.x."
  group = "maestro"
  dependsOn(extractMaestroJars)
  from(zipTree(maestroJarsDir.resolve("maestro-web.jar")))
  from({ maestroWebKtor2Jars.map { zipTree(it) } })
  relocate("io.ktor", "arbigent.shaded.io.ktor")
  mergeServiceFiles() // ktor engines register via META-INF/services; merge + relocate their entries
  exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  archiveFileName.set("maestro-web-shaded.jar")
  destinationDirectory.set(maestroShadedDir)
}

// Individual jars (not the directory) so they land on the compile/runtime classpath as
// libraries. builtBy carries the extraction task dependency to any consumer that uses this
// collection as a dependency. The plain maestro-web.jar is excluded in favour of the shaded one.
val maestroJars: FileCollection = files(
  fileTree(maestroJarsDir) {
    include("*.jar")
    exclude("maestro-web.jar")
    builtBy(extractMaestroJars)
  },
  shadeMaestroWeb,
)

// Consumed by modules via rootProject.extra: dependencies { api(rootMaestroJars()) }.
extra["maestroJars"] = maestroJars
