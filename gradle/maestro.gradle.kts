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
// 1.40.0; 2.x is distributed only as jars bundled inside the official release artifact. We pin
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

// The maestro cache lives under the shared Gradle user home, so concurrent Gradle invocations
// (CI matrix, parallel module builds) can race to write the same zip/jar while another process
// reads it. Serialize every write to the cache with a cross-process file lock; the lock file is
// never a published artifact, so creating it is harmless.
fun <T> withMaestroCacheLock(block: () -> T): T {
  maestroCacheDir.mkdirs()
  val lockFile = maestroCacheDir.resolve(".lock")
  return java.io.RandomAccessFile(lockFile, "rw").use { raf ->
    raf.channel.lock().use { block() }
  }
}

// Publish a fully-built file into its shared location atomically, so a concurrent reader never
// observes a half-written target. Copy into a sibling temp in the destination dir first (works
// even when source and target are on different filesystems), then rename it into place: rename
// within a filesystem is atomic. The source is left intact so callers can keep it as a build
// output for up-to-date checking.
fun publishFileAtomically(source: File, target: File) {
  target.parentFile.mkdirs()
  val sibling = File.createTempFile("publish", ".tmp", target.parentFile)
  try {
    source.copyTo(sibling, overwrite = true)
    java.nio.file.Files.move(
      sibling.toPath(), target.toPath(),
      java.nio.file.StandardCopyOption.REPLACE_EXISTING,
      java.nio.file.StandardCopyOption.ATOMIC_MOVE,
    )
  } finally {
    sibling.delete()
  }
}

val downloadMaestroZip = tasks.register("downloadMaestroZip") {
  description = "Download the pinned Maestro release artifact and verify its sha256 checksum."
  group = "maestro"
  outputs.file(maestroZipFile)
  outputs.upToDateWhen { maestroZipFile.exists() && sha256(maestroZipFile) == maestroZipSha256 }
  doLast {
    withMaestroCacheLock {
      if (maestroZipFile.exists() && sha256(maestroZipFile) == maestroZipSha256) return@withMaestroCacheLock
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
        publishFileAtomically(tmp, maestroZipFile)
      } finally {
        tmp.delete()
      }
    }
  }
}

// Extract into the (per-build, non-shared) build directory so Gradle's own up-to-date checking
// applies, then publish each jar into the shared cache atomically via publishMaestroJars. A
// later PR adding real-device support can extract the zip's driver-iphoneos/ products here too.
val maestroJarsStagingDir: File = layout.buildDirectory.dir("maestro/jars-staging").get().asFile
val extractMaestroJars = tasks.register<Copy>("extractMaestroJars") {
  description = "Extract the maestro-* jars arbigent depends on from the pinned release artifact."
  group = "maestro"
  dependsOn(downloadMaestroZip)
  from(zipTree(maestroZipFile)) {
    include(maestroJarEntries)
    eachFile { path = name } // flatten maestro/lib/<jar> -> <jar>
  }
  includeEmptyDirs = false
  into(maestroJarsStagingDir)
}

val publishMaestroJars = tasks.register("publishMaestroJars") {
  description = "Atomically publish the extracted maestro-* jars into the shared cache."
  group = "maestro"
  dependsOn(extractMaestroJars)
  outputs.dir(maestroJarsDir)
  doLast {
    withMaestroCacheLock {
      maestroJarsStagingDir.listFiles()?.forEach { jar ->
        publishFileAtomically(jar, maestroJarsDir.resolve(jar.name))
      }
    }
  }
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

// Shade into the (per-build, non-shared) build directory so Gradle's own up-to-date checking
// applies and concurrent builds never write the same shared jar; publishShadedMaestroWeb then
// moves it into the shared cache atomically. Read the plain maestro-web.jar from the extraction
// staging dir rather than the shared cache to avoid reading a jar another build is publishing.
val maestroShadedStagingDir: File = layout.buildDirectory.dir("maestro/shaded-staging").get().asFile
val shadeMaestroWeb = tasks.register<ShadowJar>("shadeMaestroWeb") {
  description = "Relocate ktor 2.3.13 inside maestro-web to avoid clashing with arbigent's ktor 3.x."
  group = "maestro"
  dependsOn(extractMaestroJars)
  from(zipTree(maestroJarsStagingDir.resolve("maestro-web.jar")))
  from({ maestroWebKtor2Jars.map { zipTree(it) } })
  relocate("io.ktor", "arbigent.shaded.io.ktor")
  mergeServiceFiles() // ktor engines register via META-INF/services; merge + relocate their entries
  exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  archiveFileName.set("maestro-web-shaded.jar")
  destinationDirectory.set(maestroShadedStagingDir)
}

val shadedMaestroWebJar: File = maestroShadedDir.resolve("maestro-web-shaded.jar")
val publishShadedMaestroWeb = tasks.register("publishShadedMaestroWeb") {
  description = "Atomically publish the shaded maestro-web jar into the shared cache."
  group = "maestro"
  dependsOn(shadeMaestroWeb)
  outputs.file(shadedMaestroWebJar)
  doLast {
    withMaestroCacheLock {
      publishFileAtomically(maestroShadedStagingDir.resolve("maestro-web-shaded.jar"), shadedMaestroWebJar)
    }
  }
}

// Individual jars (not the directory) so they land on the compile/runtime classpath as
// libraries. builtBy carries the publication task dependency to any consumer that uses this
// collection as a dependency. The plain maestro-web.jar is excluded in favour of the shaded one.
val maestroJars: FileCollection = files(
  fileTree(maestroJarsDir) {
    include("*.jar")
    exclude("maestro-web.jar")
    builtBy(publishMaestroJars)
  },
  files(shadedMaestroWebJar).builtBy(publishShadedMaestroWeb),
)

// Consumed by modules via rootProject.extra: dependencies { api(rootMaestroJars()) }.
extra["maestroJars"] = maestroJars
