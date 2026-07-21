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
 * [DRIVER_SOURCE_RESOURCE] (extracted from the pinned Maestro source archive at build time). We
 * mirror maestro-cli's `DriverBuilder`: copy the source to a temp dir and run
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
    // The cache dir is shared across arbigent processes (e.g. UI + CLI); serialize the
    // check-then-build so two runs cannot wipe each other's in-progress build. See [withBuildLock].
    return withBuildLock(cacheDir) {
      // Another run may have finished the build while we waited for the lock.
      if (isUpToDate(cacheDir, productsDir)) {
        arbigentInfoLog("iOS real device: reusing cached runner at ${productsDir.absolutePath}")
      } else {
        build(cacheDir, productsDir)
      }
      productsDir
    }
  }

  // Serialize the shared-cache build both across threads (synchronized on [buildLock]) and across
  // processes (an exclusive FileLock on a `.lock` file kept in the cache PARENT, so it survives the
  // cache dir being replaced). synchronized is required around the FileLock too: a second thread in
  // the same JVM locking the same channel would hit OverlappingFileLockException.
  private fun <T> withBuildLock(cacheDir: File, block: () -> T): T {
    val lockParent = cacheDir.parentFile.apply { mkdirs() }
    val lockFile = File(lockParent, "${cacheDir.name}.build.lock")
    return synchronized(buildLock) {
      java.io.RandomAccessFile(lockFile, "rw").use { raf ->
        raf.channel.lock().use { block() }
      }
    }
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
    // Build into a process-unique sibling dir and only swap it into place once complete, so a
    // partially-built or failed build never leaves a corrupt runner at the shared cache path.
    val buildDir = Files.createTempDirectory(
      cacheDir.parentFile.apply { mkdirs() }.toPath(),
      "${cacheDir.name}.building",
    ).toFile()
    val buildProductsDir = File(buildDir, "Build/Products")

    val sourceDir = Files.createTempDirectory("arbigent-ios-driver-src")
    try {
      copyDriverSource(sourceDir)
      val project = sourceDir.resolve("maestro-driver-ios.xcodeproj")
      check(Files.exists(project)) {
        "Bundled iOS driver source is missing maestro-driver-ios.xcodeproj at $project"
      }

      // `-allowProvisioningUpdates` creates a fresh provisioning profile on first run, but the
      // build description is sometimes computed before the profile lands on disk ("Build input
      // file cannot be found: …/<uuid>.mobileprovision"). The profile exists by the time the run
      // fails, so a single clean rebuild picks it up. Retry once on that transient race.
      var result = runXcodebuild(project, buildDir)
      if (!result.isSuccess && isTransientProvisioningFailure(result)) {
        arbigentInfoLog("iOS real device: transient provisioning race on first build; retrying once")
        result = runXcodebuild(project, buildDir)
      }
      if (!result.isSuccess) {
        // xcodebuild echoes the full invocation (including DEVELOPMENT_TEAM=<teamId>) into its
        // output; redact the team id before persisting so it never lands in cleartext on disk.
        val masked = IosCodeSigningTeamResolver.maskTeamId(teamId)
        cacheDir.parentFile.mkdirs()
        val log = File(cacheDir.parentFile, "${cacheDir.name}-xcodebuild-output.log").apply {
          writeText((result.stdout + "\n" + result.stderr).replace(teamId, masked))
        }
        throw IllegalStateException(
          "Failed to build the iOS XCTest runner for the connected device. " +
            "Check signing (team ${IosCodeSigningTeamResolver.maskTeamId(teamId)}) and that Xcode can " +
            "provision this device. Build log: ${log.absolutePath}"
        )
      }
      check(buildProductsDir.isDirectory && buildProductsDir.walkTopDown().any { it.extension == "xctestrun" }) {
        "xcodebuild reported success but no .xctestrun was produced under ${buildProductsDir.absolutePath}"
      }
      writeMarker(buildDir)
      // Swap the freshly built dir into place. We hold the build lock, so no other run is building;
      // replace any stale cache dir and move atomically where the filesystem supports it.
      cacheDir.deleteRecursively()
      try {
        Files.move(buildDir.toPath(), cacheDir.toPath(), StandardCopyOption.ATOMIC_MOVE)
      } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(buildDir.toPath(), cacheDir.toPath())
      }
      arbigentInfoLog("iOS real device: runner built at ${productsDir.absolutePath}")
    } finally {
      sourceDir.toFile().deleteRecursively()
      buildDir.deleteRecursively()
    }
  }

  private fun runXcodebuild(project: java.nio.file.Path, derivedDataPath: File): ArbigentCommandResult =
    executor.execute(
      listOf(
        "xcodebuild",
        "clean",
        "build-for-testing",
        "-project", project.toString(),
        "-scheme", "maestro-driver-ios",
        "-destination", "platform=iOS,id=$deviceUdid",
        "-allowProvisioningUpdates",
        "-derivedDataPath", derivedDataPath.absolutePath,
        "DEVELOPMENT_TEAM=$teamId",
        "CODE_SIGN_IDENTITY=Apple Development",
      ),
      timeoutMs = xcodebuildTimeoutMs(),
    )

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

  // First-run `-allowProvisioningUpdates` races: a just-created provisioning profile the build
  // description referenced before it was written to disk. Re-running finds the now-present profile.
  private fun isTransientProvisioningFailure(result: ArbigentCommandResult): Boolean {
    val text = result.stdout + result.stderr
    return text.contains(".mobileprovision") &&
      (text.contains("Build input file cannot be found") || text.contains("No profiles for"))
  }

  private fun xcodebuildTimeoutMs(): Long {
    val seconds = System.getenv("MAESTRO_XCODEBUILD_WAIT_TIME")?.trim()?.toLongOrNull()
      ?: DEFAULT_XCODEBUILD_WAIT_SECONDS
    return seconds * 1_000
  }

  public companion object {
    public const val DRIVER_SOURCE_RESOURCE: String = "ios-real-driver/runner"
    private const val MARKER_FILE = "arbigent-driver.properties"

    // In-process guard around the cross-process FileLock (a channel can't be locked twice in one JVM).
    private val buildLock = Any()

    // First builds (clean + provisioning) routinely exceed maestro's 120s default; give xcodebuild
    // more room while still honoring an explicit MAESTRO_XCODEBUILD_WAIT_TIME override.
    private const val DEFAULT_XCODEBUILD_WAIT_SECONDS = 600L
  }
}

/** `~/.arbigent`, the on-disk home for cached artifacts shared across arbigent runs. */
internal fun arbigentHomeDir(): File =
  File(System.getProperty("user.home"), ".arbigent").apply { mkdirs() }
