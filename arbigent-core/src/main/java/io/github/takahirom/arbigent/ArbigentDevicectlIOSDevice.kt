package io.github.takahirom.arbigent

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.runCatching
import device.IOSDevice
import ios.devicectl.DeviceControlIOSDevice
import util.IOSLaunchArguments.toIOSLaunchArguments
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * App-lifecycle controller for a physical iPhone, backed by `xcrun devicectl`.
 *
 * maestro 2.7's [DeviceControlIOSDevice] leaves the app-lifecycle methods as
 * `TODO("Not yet implemented")` (they throw [NotImplementedError]), so launching or installing the
 * app-under-test on a real device would crash. arbigent's [ios.LocalIOSDevice] routes UI operations
 * (tap, screenshot, hierarchy, ...) to the XCTest HTTP runner and only lifecycle operations to this
 * controller, so we implement exactly those over devicectl and delegate everything else to maestro's
 * class (its `uninstall` works; the remaining delegated methods are never reached through
 * `LocalIOSDevice`).
 *
 * @param coreDeviceIdentifier the CoreDevice identifier (`--device` argument), i.e.
 *   `DeviceCtlResponse.Device.identifier` — the same id maestro's controller uses.
 */
public class ArbigentDevicectlIOSDevice(
  private val coreDeviceIdentifier: String,
  private val executor: ArbigentCommandExecutor = DefaultArbigentCommandExecutor(),
  private val delegate: IOSDevice = DeviceControlIOSDevice(coreDeviceIdentifier),
) : IOSDevice by delegate {

  override fun launch(id: String, launchArguments: Map<String, Any>) {
    // --terminate-existing so re-running a scenario relaunches cleanly instead of attaching.
    val command = mutableListOf(
      "xcrun", "devicectl", "device", "process", "launch",
      "--terminate-existing",
      "--device", coreDeviceIdentifier,
    )
    // devicectl parses `-`-prefixed tokens as its own options wherever they appear (Swift
    // ArgumentParser), so terminate option parsing with `--`. Per `devicectl device process
    // launch --help` everything after `--` is positional (<bundle-identifier-or-path> then
    // <command-line-arguments>...), so a malformed app id can't be read as a devicectl option.
    // Convert the launch arguments with maestro's own toIOSLaunchArguments so key/value pairs
    // (e.g. "-cartColor" "Orange") match the semantics used on simulators.
    command.add("--")
    command.add(id)
    command.addAll(launchArguments.toIOSLaunchArguments())
    val result = executor.execute(command)
    check(result.isSuccess) {
      "Failed to launch $id on the connected device: ${result.stderr.ifBlank { result.stdout }}"
    }
  }

  override fun install(stream: InputStream) {
    // The stream is a zipped .app bundle. Unzip to a temp dir, locate the .app, install via devicectl.
    val work = File.createTempFile("arbigent-ios-install", "").apply {
      delete()
      mkdirs()
    }
    try {
      unzip(stream, work)
      val app = work.walkTopDown().firstOrNull { it.isDirectory && it.extension == "app" }
        ?: throw IllegalStateException("No .app bundle found in the provided install stream")
      val result = executor.execute(
        listOf("xcrun", "devicectl", "device", "install", "app", "--device", coreDeviceIdentifier, app.absolutePath)
      )
      check(result.isSuccess) {
        "Failed to install ${app.name} on the connected device: ${result.stderr.ifBlank { result.stdout }}"
      }
    } finally {
      work.deleteRecursively()
    }
  }

  override fun clearAppState(id: String) {
    // devicectl exposes no app-state clear, and uninstall/reinstall would be destructive without a
    // reliable reinstall path. Silently skipping made clearState() falsely report success and let
    // scenarios run against stale data, so fail explicitly instead.
    throw UnsupportedOperationException(
      "clearAppState($id) is not supported on a physical iOS device via devicectl. Remove clearState " +
        "from this scenario, or uninstall/reinstall the app between runs manually."
    )
  }

  override fun openLink(link: String): Result<Unit, Throwable> = runCatching {
    // `devicectl device process launch` requires a <bundle-identifier-or-path> positional (see its
    // --help); there is no devicectl verb that opens an arbitrary URL against the system handler, so
    // --payload-url alone cannot launch anything. Fail loudly rather than run a command that always
    // errors while pretending to be supported.
    throw UnsupportedOperationException(
      "openLink($link) is not supported on a physical iOS device via devicectl. Trigger the deep link " +
        "from within the app under test instead."
    )
  }

  private fun unzip(stream: InputStream, targetDir: File) {
    val targetRoot = targetDir.canonicalFile
    ZipInputStream(stream).use { zip ->
      var entry = zip.nextEntry
      while (entry != null) {
        val currentEntry = entry!!
        // Canonicalize the destination before any filesystem use, then confirm it stays within the
        // target directory (zip-slip guard). Path.startsWith compares whole path components, so a
        // traversing entry (../…) or a sibling like "<target>-evil" cannot escape.
        val outFile = File(targetRoot, currentEntry.name).canonicalFile
        if (!outFile.toPath().startsWith(targetRoot.toPath())) {
          throw IOException("Zip entry escapes target directory: ${currentEntry.name}")
        }
        if (currentEntry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()
          outFile.outputStream().use { zip.copyTo(it) }
        }
        entry = zip.nextEntry
      }
    }
  }
}
