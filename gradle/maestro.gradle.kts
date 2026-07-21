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

// Stage into a unique temp directory under the (per-checkout) build directory rather than a fixed
// path, so two Gradle invocations sharing the same checkout never overwrite each other's staging
// jars while one is mid-extract/shade. Each staged jar is then published into the shared cache
// atomically. The temp dirs are created lazily (only when a maestro task actually runs) so
// unrelated builds don't litter the build directory. A later PR adding real-device support can
// extract the zip's driver-iphoneos/ products into the same staging area.
val maestroStagingBase: File = layout.buildDirectory.dir("maestro").get().asFile
val maestroJarsStagingDir: File by lazy {
  maestroStagingBase.mkdirs()
  java.nio.file.Files.createTempDirectory(maestroStagingBase.toPath(), "jars-staging").toFile()
}
val extractMaestroJars = tasks.register<Copy>("extractMaestroJars") {
  description = "Extract the maestro-* jars arbigent depends on from the pinned release artifact."
  group = "maestro"
  dependsOn(downloadMaestroZip)
  from(zipTree(maestroZipFile)) {
    include(maestroJarEntries)
    eachFile { path = name } // flatten maestro/lib/<jar> -> <jar>
  }
  includeEmptyDirs = false
  into(java.util.concurrent.Callable { maestroJarsStagingDir })
}

val publishMaestroJars = tasks.register("publishMaestroJars") {
  description = "Atomically publish the extracted maestro-* jars into the shared cache."
  group = "maestro"
  dependsOn(extractMaestroJars)
  outputs.dir(maestroJarsDir)
  doLast {
    val staged = maestroJarsStagingDir.listFiles().orEmpty()
    withMaestroCacheLock {
      staged.forEach { jar ->
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

// Shade into a unique temp directory under the build directory (as for extraction) so concurrent
// builds in the same checkout never write the same staging jar; publishShadedMaestroWeb then moves
// it into the shared cache atomically. Read the plain maestro-web.jar from the extraction staging
// dir rather than the shared cache to avoid reading a jar another build is publishing.
val maestroShadedStagingDir: File by lazy {
  maestroStagingBase.mkdirs()
  java.nio.file.Files.createTempDirectory(maestroStagingBase.toPath(), "shaded-staging").toFile()
}
val shadeMaestroWeb = tasks.register<ShadowJar>("shadeMaestroWeb") {
  description = "Relocate ktor 2.3.13 inside maestro-web to avoid clashing with arbigent's ktor 3.x."
  group = "maestro"
  dependsOn(extractMaestroJars)
  from({ zipTree(maestroJarsStagingDir.resolve("maestro-web.jar")) })
  from({ maestroWebKtor2Jars.map { zipTree(it) } })
  relocate("io.ktor", "arbigent.shaded.io.ktor")
  mergeServiceFiles() // ktor engines register via META-INF/services; merge + relocate their entries
  exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
  archiveFileName.set("maestro-web-shaded.jar")
  destinationDirectory.set(layout.dir(provider { maestroShadedStagingDir }))
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

// Pinned Maestro version, exposed so modules can key caches (e.g. the built iOS real-device
// runner) on the exact runner source they ship.
extra["maestroVersion"] = maestroVersion

// --- iOS real-device driver source ------------------------------------------------------
//
// Building the XCTest runner for a physical iPhone requires re-signing it with the user's
// Apple team (maestro's mobile-dev signature is useless off their machines). maestro does this
// in its `maestro-cli` module (DriverBuilder), which arbigent does not depend on. We therefore
// build the runner ourselves, from the COMPLETE runner Xcode project in the pinned source
// archive (`maestro-ios-xctest-runner/`). Note: the `driver/ios` copy bundled inside
// maestro-cli-<version>.jar is incomplete — it omits the MaestroDriverLib sub-project its own
// xcodeproj references — so the source archive, not that jar resource, is the source of truth.
val maestroSourceSha256 = "6ff65eba63ec4910df59ddb1007044f964753c3b4a66464447c6676594809c0b"
val maestroSourceUrl =
  "https://github.com/mobile-dev-inc/Maestro/archive/refs/tags/cli-$maestroVersion.tar.gz"
val maestroSourceTar: File = maestroCacheDir.resolve("maestro-source.tar.gz")
val maestroDriverSourceDir: File = maestroCacheDir.resolve("ios-driver-source")
// Path prefix inside the GitHub source tarball (repo name + tag) that wraps every entry.
val maestroSourceRootPrefix = "Maestro-cli-$maestroVersion"

val downloadMaestroSource = tasks.register("downloadMaestroSource") {
  description = "Download the pinned Maestro source archive (carrier of the iOS runner Xcode project)."
  group = "maestro"
  outputs.file(maestroSourceTar)
  outputs.upToDateWhen { maestroSourceTar.exists() && sha256(maestroSourceTar) == maestroSourceSha256 }
  doLast {
    maestroCacheDir.mkdirs()
    if (maestroSourceTar.exists() && sha256(maestroSourceTar) == maestroSourceSha256) return@doLast
    logger.lifecycle("Downloading Maestro source $maestroVersion from $maestroSourceUrl")
    val tmp = File.createTempFile("maestro-source", ".tar.gz", maestroCacheDir)
    try {
      uri(maestroSourceUrl).toURL().openStream().use { input ->
        tmp.outputStream().use { output -> input.copyTo(output) }
      }
      val actual = sha256(tmp)
      check(actual == maestroSourceSha256) {
        "Maestro source checksum mismatch for $maestroSourceUrl: expected $maestroSourceSha256 but got $actual"
      }
      tmp.copyTo(maestroSourceTar, overwrite = true)
    } finally {
      tmp.delete()
    }
  }
}

val extractMaestroIosDriverSource = tasks.register<Copy>("extractMaestroIosDriverSource") {
  description = "Extract the complete iOS XCTest runner Xcode project used to build a signed real-device runner."
  group = "maestro"
  dependsOn(downloadMaestroSource)
  from(tarTree(resources.gzip(maestroSourceTar))) {
    include("$maestroSourceRootPrefix/maestro-ios-xctest-runner/**")
    // Strip the "<root>/maestro-ios-xctest-runner/" prefix so maestro-driver-ios.xcodeproj sits
    // at the top of the extracted tree.
    eachFile { relativePath = RelativePath(true, *relativePath.segments.drop(2).toTypedArray()) }
  }
  includeEmptyDirs = false
  into(maestroDriverSourceDir)
}

// Packaged into arbigent-core resources under ios-real-driver/runner/**; the runtime runner
// builder copies it out and runs xcodebuild against maestro-driver-ios.xcodeproj.
val maestroIosDriverSource: FileCollection = fileTree(maestroDriverSourceDir) {
  builtBy(extractMaestroIosDriverSource)
}
extra["maestroIosDriverSource"] = maestroIosDriverSource
