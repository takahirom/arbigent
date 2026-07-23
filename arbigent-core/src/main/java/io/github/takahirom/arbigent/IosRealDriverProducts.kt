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
    // Validate the cache and (if needed) build entirely under the build lock. A cache-hit
    // validation done outside the lock could return a products dir that a concurrent run is
    // mid-way through deleting and rebuilding, and its runner-source checksum could open the
    // bundled-source jar FileSystem while another thread closes it. Holding the lock across the
    // whole check-then-build serializes both against other runs (process) and threads (JVM).
    return withBuildLock(cacheDir) {
      if (isUpToDate(cacheDir, productsDir)) {
        arbigentInfoLog("iOS real device: reusing cached runner at ${productsDir.absolutePath}")
      } else {
        build(cacheDir, productsDir)
      }
      // Accepted tradeoff: the returned dir is validated/built under the lock but not lifetime-
      // protected once the lock is released — a *different process* could later replace this cache
      // (same device+team) while a caller still holds the path. Concurrent multi-process runs
      // against the same device+team are out of scope, so we do not pin or copy the products here.
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
    // Every input that changes the signed products must be part of the reuse decision: schema (so a
    // format change forces a rebuild), maestro version, team, target device, Xcode/toolchain and the
    // exact runner source. The device is also in the cache path, but validating it here guards against
    // a hand-edited marker or path collision.
    if (props.getProperty("schemaVersion") != CACHE_SCHEMA_VERSION) return false
    if (props.getProperty("maestroVersion") != maestroVersion) return false
    if (props.getProperty("teamHash") != teamHash()) return false
    if (props.getProperty("deviceHash") != deviceHash()) return false
    if (props.getProperty("xcodeVersion") != xcodeVersion()) return false
    if (props.getProperty("runnerSourceChecksum") != runnerSourceChecksum()) return false
    // Free (7-day) provisioning profiles expire; a cached-but-expired runner would fail to install.
    // Rebuild if the embedded profile is expired or does not cover the target device.
    if (!isProvisioningProfileUsable(productsDir)) {
      arbigentInfoLog("iOS real device: cached runner's provisioning profile is expired or missing this device; rebuilding")
      return false
    }
    return true
  }

  /** Decodes the built runner's embedded.mobileprovision and checks expiry + device eligibility. */
  private fun isProvisioningProfileUsable(productsDir: File): Boolean {
    val profile = productsDir.walkTopDown().firstOrNull { it.name == "embedded.mobileprovision" }
      ?: return false
    val decoded = executor.execute(listOf("security", "cms", "-D", "-i", profile.absolutePath))
    if (!decoded.isSuccess || decoded.stdout.isBlank()) return false
    return MobileProvisionInspector.isUsable(decoded.stdout, deviceUdid, java.time.Instant.now())
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
    props["schemaVersion"] = CACHE_SCHEMA_VERSION
    props["maestroVersion"] = maestroVersion
    props["teamHash"] = teamHash()
    props["deviceHash"] = deviceHash()
    props["xcodeVersion"] = xcodeVersion()
    props["runnerSourceChecksum"] = runnerSourceChecksum()
    File(cacheDir, MARKER_FILE).outputStream().use { props.store(it, "arbigent iOS real-device runner") }
  }

  /**
   * Resolves the bundled driver/ios resource tree and runs [block] against its root, handling both
   * jar and exploded-directory layouts. When the resource lives in a jar whose FileSystem we had to
   * open ourselves, we close it afterwards; a FileSystem the JVM already owns (e.g. the app jar) is
   * left open so we don't break other consumers.
   */
  private fun <T> withDriverSourceRoot(block: (Path) -> T): T {
    val url = javaClass.classLoader.getResource(DRIVER_SOURCE_RESOURCE)
      ?: throw IllegalStateException(
        "Bundled iOS driver source not found on classpath at $DRIVER_SOURCE_RESOURCE. " +
          "This build was produced without the iOS real-device runner source."
      )
    val uri = url.toURI()
    if (uri.scheme != "jar") return block(Paths.get(uri))
    var createdFs = false
    val fs = try {
      // The JVM already owns this jar FileSystem (e.g. the app jar); borrow it and leave it open.
      FileSystems.getFileSystem(uri)
    } catch (e: FileSystemNotFoundException) {
      try {
        createdFs = true
        FileSystems.newFileSystem(uri, emptyMap<String, Any>())
      } catch (e: java.nio.file.FileSystemAlreadyExistsException) {
        // Another caller created it between our lookup and creation; borrow it without closing so
        // we don't yank the FileSystem out from under that caller mid-walk.
        createdFs = false
        FileSystems.getFileSystem(uri)
      }
    }
    return try {
      block(fs.getPath("/$DRIVER_SOURCE_RESOURCE"))
    } finally {
      if (createdFs) fs.close()
    }
  }

  /** Copies the bundled driver/ios resource tree to [target], handling jar and file layouts. */
  private fun copyDriverSource(target: Path): Unit = withDriverSourceRoot { sourceRoot ->
    Files.walk(sourceRoot).use { paths ->
      paths.filter { Files.isRegularFile(it) }.forEach { path ->
        val relative = sourceRoot.relativize(path).toString()
        val dest = target.resolve(relative)
        Files.createDirectories(dest.parent)
        Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING)
      }
    }
  }

  // Fingerprint of the packaged runner source (relative paths + contents), so a runner cached from a
  // different or tampered source tree is rebuilt even though the maestro version matches.
  private fun runnerSourceChecksum(): String = withDriverSourceRoot { sourceRoot ->
    val digest = MessageDigest.getInstance("SHA-256")
    Files.walk(sourceRoot).use { paths ->
      paths.filter { Files.isRegularFile(it) }.sorted().forEach { path ->
        digest.update(sourceRoot.relativize(path).toString().toByteArray())
        digest.update(Files.readAllBytes(path))
      }
    }
    digest.digest().take(8).joinToString("") { "%02x".format(it) }
  }

  private fun xcodeVersion(): String =
    executor.execute(listOf("xcodebuild", "-version")).stdout.trim().replace("\n", " ").ifBlank { "unknown" }

  // Scope the cache path by team AND device so a runner signed/provisioned for one iPhone is never
  // reused for another; other build inputs (Xcode, source, schema) are validated via the marker.
  private fun cacheDir(): File =
    File(arbigentHomeDir(), "ios-real-driver/$maestroVersion/${teamHash()}/${deviceHash()}")

  // Hash the team id so it never appears in cleartext in filesystem paths.
  private fun teamHash(): String = shortHash(teamId)

  // Hash the device udid too, so real UDIDs never appear in cleartext on disk.
  private fun deviceHash(): String = shortHash(deviceUdid)

  private fun shortHash(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
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

    // Bump when the marker format or the set of validated inputs changes, so old caches are ignored.
    private const val CACHE_SCHEMA_VERSION = "2"

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

/**
 * Reads the two fields that decide whether a cached, signed runner is still usable from a decoded
 * `embedded.mobileprovision` plist (as produced by `security cms -D -i`). Kept string-based and pure
 * so it is unit-testable without a real profile.
 */
internal object MobileProvisionInspector {
  private val expirationRegex = Regex("""<key>ExpirationDate</key>\s*<date>([^<]+)</date>""")
  private val provisionedDevicesRegex =
    Regex("""<key>ProvisionedDevices</key>\s*<array>(.*?)</array>""", RegexOption.DOT_MATCHES_ALL)
  private val stringRegex = Regex("""<string>([^<]+)</string>""")

  fun expirationDate(plistXml: String): java.time.Instant? =
    expirationRegex.find(plistXml)?.groupValues?.get(1)?.let {
      runCatching { java.time.Instant.parse(it.trim()) }.getOrNull()
    }

  /** The provisioned device UDIDs, or null when the profile lists none (wildcard/enterprise). */
  fun provisionedDevices(plistXml: String): List<String>? {
    val block = provisionedDevicesRegex.find(plistXml)?.groupValues?.get(1) ?: return null
    return stringRegex.findAll(block).map { it.groupValues[1].trim() }.toList()
  }

  fun isUsable(plistXml: String, deviceUdid: String, now: java.time.Instant): Boolean {
    val expiry = expirationDate(plistXml) ?: return false
    if (!expiry.isAfter(now)) return false
    val devices = provisionedDevices(plistXml) ?: return true
    return devices.any { it.equals(deviceUdid, ignoreCase = true) }
  }
}
