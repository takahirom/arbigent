import java.io.File
import java.security.MessageDigest

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
      tmp.copyTo(maestroZipFile, overwrite = true)
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

// Individual jars (not the directory) so they land on the compile/runtime classpath as
// libraries. builtBy carries the extraction task dependency to any consumer that uses this
// collection as a dependency.
val maestroJars: FileCollection = fileTree(maestroJarsDir) {
  include("*.jar")
  builtBy(extractMaestroJars)
}

// Consumed by modules via rootProject.extra: dependencies { api(rootMaestroJars()) }.
extra["maestroJars"] = maestroJars
