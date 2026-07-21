package io.github.takahirom.arbigent

import java.io.File
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

/**
 * Builds and caches the XCTest runner for a physical iPhone, re-signed with the user's Apple team.
 *
 * The runner Xcode project + Swift sources ship as a classpath resource under
 * [DRIVER_SOURCE_RESOURCE] (extracted from the pinned maestro-cli jar at build time). We mirror
 * maestro-cli's `DriverBuilder`: copy the source to a temp dir and run
 * `xcodebuild clean build-for-testing` with `DEVELOPMENT_TEAM` and `CODE_SIGN_IDENTITY`. Products
 * land in `<cache>/Build/Products`, which is what [xcuitest.installer.LocalXCTestInstaller] consumes
 * as `sourceDirectory` for `IOSDeviceType.REAL`.
 *
 * Products are cached under `~/.arbigent/ios-real-driver/<maestroVersion>/<teamHash>/` and reused
 * when a marker file matches the current maestro version and team; a missing `.xctestrun` or a
 * version/team mismatch triggers a rebuild.
 */
public class IosRealDriverProducts(
  private val teamId: String,
  private val deviceUdid: String,
  private val executor: ArbigentCommandExecutor = DefaultArbigentCommandExecutor(),
  private val maestroVersion: String = BuildConfig.MAESTRO_VERSION,
) {
  /** Returns the `Build/Products` directory, building the signed runner first if necessary. */
  public fun resolveBuildProductsDir(): File {
    val cacheDir = cacheDir()
    val productsDir = File(cacheDir, "Build/Products")
    if (isUpToDate(cacheDir, productsDir)) {
      arbigentInfoLog("iOS real device: reusing cached runner at ${productsDir.absolutePath}")
      return productsDir
    }
    build(cacheDir, productsDir)
    return productsDir
  }

  private fun isUpToDate(cacheDir: File, productsDir: File): Boolean {
    val marker = File(cacheDir, MARKER_FILE)
    if (!marker.exists() || !productsDir.isDirectory) return false
    val hasXctestRun = productsDir.walkTopDown().any { it.extension == "xctestrun" }
    if (!hasXctestRun) return false
    val props = Properties().apply { marker.inputStream().use { load(it) } }
    return props.getProperty("maestroVersion") == maestroVersion &&
      props.getProperty("teamHash") == teamHash()
  }

  private fun build(cacheDir: File, productsDir: File) {
    arbigentInfoLog(
      "iOS real device: building signed XCTest runner (team ${IosCodeSigningTeamResolver.maskTeamId(teamId)}); " +
        "first build can take several minutes"
    )
    // Rebuild from a clean cache dir to avoid stale products from an older maestro/team.
    cacheDir.deleteRecursively()
    cacheDir.mkdirs()

    val sourceDir = Files.createTempDirectory("arbigent-ios-driver-src")
    try {
      copyDriverSource(sourceDir)
      val project = sourceDir.resolve("maestro-driver-ios.xcodeproj")
      check(Files.exists(project)) {
        "Bundled iOS driver source is missing maestro-driver-ios.xcodeproj at $project"
      }
      val result = executor.execute(
        listOf(
          "xcodebuild",
          "clean",
          "build-for-testing",
          "-project", project.toString(),
          "-scheme", "maestro-driver-ios",
          "-destination", "platform=iOS,id=$deviceUdid",
          "-allowProvisioningUpdates",
          "-derivedDataPath", cacheDir.absolutePath,
          "DEVELOPMENT_TEAM=$teamId",
          "CODE_SIGN_IDENTITY=Apple Development",
        ),
        timeoutMs = xcodebuildTimeoutMs(),
      )
      if (!result.isSuccess) {
        // xcodebuild echoes the full invocation (including DEVELOPMENT_TEAM=<teamId>) into its
        // output; redact the team id before persisting so it never lands in cleartext on disk.
        val masked = IosCodeSigningTeamResolver.maskTeamId(teamId)
        val log = File(cacheDir, "xcodebuild-output.log").apply {
          writeText((result.stdout + "\n" + result.stderr).replace(teamId, masked))
        }
        throw IllegalStateException(
          "Failed to build the iOS XCTest runner for the connected device. " +
            "Check signing (team ${IosCodeSigningTeamResolver.maskTeamId(teamId)}) and that Xcode can " +
            "provision this device. Build log: ${log.absolutePath}"
        )
      }
      check(productsDir.isDirectory && productsDir.walkTopDown().any { it.extension == "xctestrun" }) {
        "xcodebuild reported success but no .xctestrun was produced under ${productsDir.absolutePath}"
      }
      writeMarker(cacheDir)
      arbigentInfoLog("iOS real device: runner built at ${productsDir.absolutePath}")
    } finally {
      sourceDir.toFile().deleteRecursively()
    }
  }

  private fun writeMarker(cacheDir: File) {
    val props = Properties()
    props["maestroVersion"] = maestroVersion
    props["teamHash"] = teamHash()
    File(cacheDir, MARKER_FILE).outputStream().use { props.store(it, "arbigent iOS real-device runner") }
  }

  /** Copies the bundled driver/ios resource tree to [target], handling jar and file layouts. */
  private fun copyDriverSource(target: Path) {
    val url = javaClass.classLoader.getResource(DRIVER_SOURCE_RESOURCE)
      ?: throw IllegalStateException(
        "Bundled iOS driver source not found on classpath at $DRIVER_SOURCE_RESOURCE. " +
          "This build was produced without the iOS real-device runner source."
      )
    val uri = url.toURI()
    val sourceRoot: Path = if (uri.scheme == "jar") {
      val fs = try {
        FileSystems.getFileSystem(uri)
      } catch (e: FileSystemNotFoundException) {
        FileSystems.newFileSystem(uri, emptyMap<String, Any>())
      }
      fs.getPath("/$DRIVER_SOURCE_RESOURCE")
    } else {
      Paths.get(uri)
    }
    Files.walk(sourceRoot).use { paths ->
      paths.filter { Files.isRegularFile(it) }.forEach { path ->
        val relative = sourceRoot.relativize(path).toString()
        val dest = target.resolve(relative)
        Files.createDirectories(dest.parent)
        Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }

  private fun cacheDir(): File =
    File(arbigentHomeDir(), "ios-real-driver/$maestroVersion/${teamHash()}")

  // Hash the team id so it never appears in cleartext in filesystem paths.
  private fun teamHash(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(teamId.toByteArray())
    return digest.take(8).joinToString("") { "%02x".format(it) }
  }

  private fun xcodebuildTimeoutMs(): Long {
    val seconds = System.getenv("MAESTRO_XCODEBUILD_WAIT_TIME")?.trim()?.toLongOrNull()
      ?: DEFAULT_XCODEBUILD_WAIT_SECONDS
    return seconds * 1_000
  }

  public companion object {
    public const val DRIVER_SOURCE_RESOURCE: String = "ios-real-driver/driver/ios"
    private const val MARKER_FILE = "arbigent-driver.properties"

    // First builds (clean + provisioning) routinely exceed maestro's 120s default; give xcodebuild
    // more room while still honoring an explicit MAESTRO_XCODEBUILD_WAIT_TIME override.
    private const val DEFAULT_XCODEBUILD_WAIT_SECONDS = 600L
  }
}

/** `~/.arbigent`, the on-disk home for cached artifacts shared across arbigent runs. */
internal fun arbigentHomeDir(): File =
  File(System.getProperty("user.home"), ".arbigent").apply { mkdirs() }
