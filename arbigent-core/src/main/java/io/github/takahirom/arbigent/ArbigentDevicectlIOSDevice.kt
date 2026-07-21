package io.github.takahirom.arbigent

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.runCatching
import device.IOSDevice
import ios.devicectl.DeviceControlIOSDevice
import java.io.File
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
      id,
    )
    // devicectl parses `-`-prefixed tokens as its own options wherever they appear (Swift
    // ArgumentParser), so terminate option parsing with `--` before passing app launch arguments.
    if (launchArguments.isNotEmpty()) {
      command.add("--")
      launchArguments.values.forEach { command.add(it.toString()) }
    }
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
    // devicectl exposes no app-state clear; terminating is the closest safe behavior. Uninstall
    // would be destructive without a reinstall path, so we log and skip rather than throw, keeping
    // flows that call clearState from crashing on a real device.
    arbigentInfoLog("iOS real device: clearAppState($id) is not supported via devicectl; skipping")
  }

  override fun openLink(link: String): Result<Unit, Throwable> = runCatching {
    val result = executor.execute(
      listOf("xcrun", "devicectl", "device", "process", "launch", "--device", coreDeviceIdentifier, "--payload-url", link)
    )
    check(result.isSuccess) {
      "Failed to open link on the connected device: ${result.stderr.ifBlank { result.stdout }}"
    }
  }

  private fun unzip(stream: InputStream, targetDir: File) {
    ZipInputStream(stream).use { zip ->
      var entry = zip.nextEntry
      while (entry != null) {
        val outFile = File(targetDir, entry.name)
        // Guard against zip-slip.
        check(outFile.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
          "Zip entry escapes target directory: ${entry!!.name}"
        }
        if (entry.isDirectory) {
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
