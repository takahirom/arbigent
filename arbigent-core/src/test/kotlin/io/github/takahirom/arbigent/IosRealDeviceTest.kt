package io.github.takahirom.arbigent

import device.IOSDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IosRealDeviceTest {

  /** Records commands and returns canned results keyed by the first two args. */
  private class FakeExecutor(
    private val responses: Map<String, ArbigentCommandResult> = emptyMap(),
    private val default: ArbigentCommandResult = ArbigentCommandResult(0, "", ""),
  ) : ArbigentCommandExecutor {
    val commands = mutableListOf<List<String>>()
    override fun execute(command: List<String>, timeoutMs: Long): ArbigentCommandResult {
      commands += command
      val key = command.take(2).joinToString(" ")
      return responses[key] ?: default
    }
  }

  // --- Team resolution -------------------------------------------------------------------

  @Test
  fun autoDetect_singleAppleDevelopmentIdentity_returnsTeamId() {
    val output = """
      Policy: Code Signing
        Matching identities
        1) A1B2C3D4 "Apple Development: Jane Doe (ABCDE12345)"
           1 valid identities found
    """.trimIndent()
    assertEquals("ABCDE12345", IosCodeSigningTeamResolver.autoDetect(output))
  }

  @Test
  fun autoDetect_noIdentity_throwsActionableError() {
    val e = assertFailsWith<IllegalStateException> {
      IosCodeSigningTeamResolver.autoDetect("  0 valid identities found")
    }
    assertTrue(e.message!!.contains(ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID))
  }

  @Test
  fun autoDetect_multipleTeams_throws() {
    val output = """
      1) AAAA "Apple Development: A (TEAM111111)"
      2) BBBB "Apple Development: B (TEAM222222)"
    """.trimIndent()
    assertFailsWith<IllegalStateException> { IosCodeSigningTeamResolver.autoDetect(output) }
  }

  @Test
  fun autoDetect_sameTeamTwice_isSingle() {
    val output = """
      1) AAAA "Apple Development: A (TEAM111111)"
      2) BBBB "Apple Development: A2 (TEAM111111)"
    """.trimIndent()
    assertEquals("TEAM111111", IosCodeSigningTeamResolver.autoDetect(output))
  }

  @Test
  fun resolve_prefersExplicitConfigOverEnvAndAutoDetect() {
    val executor = FakeExecutor()
    val teamId = IosCodeSigningTeamResolver.resolve(
      config = ArbigentIosRealDeviceConfiguration(appleTeamId = "CFGCFG7890"),
      env = { "ENVENV1234" },
      executor = executor,
    )
    assertEquals("CFGCFG7890", teamId)
    assertTrue(executor.commands.isEmpty(), "auto-detect should not run when config is set")
  }

  @Test
  fun resolve_fallsBackToEnvBeforeAutoDetect() {
    val executor = FakeExecutor()
    val teamId = IosCodeSigningTeamResolver.resolve(
      config = ArbigentIosRealDeviceConfiguration(appleTeamId = null),
      env = { name -> if (name == ArbigentIosRealDeviceSettings.ENV_APPLE_TEAM_ID) "ENVENV1234" else null },
      executor = executor,
    )
    assertEquals("ENVENV1234", teamId)
    assertTrue(executor.commands.isEmpty())
  }

  @Test
  fun resolve_rejectsMalformedTeamId() {
    assertFailsWith<IllegalArgumentException> {
      IosCodeSigningTeamResolver.resolve(
        config = ArbigentIosRealDeviceConfiguration(appleTeamId = "too-short"),
        env = { null },
        executor = FakeExecutor(),
      )
    }
  }

  @Test
  fun maskTeamId_keepsOnlyFirstTwoChars() {
    assertEquals("AB********", IosCodeSigningTeamResolver.maskTeamId("ABCDE12345"))
    assertTrue(!IosCodeSigningTeamResolver.maskTeamId("ABCDE12345").contains("CDE"))
  }

  // --- Port / device id resolution -------------------------------------------------------

  @Test
  fun resolvedPort_defaultsToRunnerPort() {
    ArbigentIosRealDeviceSettings.current = ArbigentIosRealDeviceConfiguration()
    assertEquals(ArbigentIosRealDeviceSettings.DEFAULT_PORT, ArbigentIosRealDeviceSettings.resolvedPort { null })
  }

  @Test
  fun resolvedPort_configWinsOverEnv() {
    ArbigentIosRealDeviceSettings.current = ArbigentIosRealDeviceConfiguration(port = 30000)
    assertEquals(30000, ArbigentIosRealDeviceSettings.resolvedPort { "40000" })
  }

  @Test
  fun resolvedDeviceId_fallsBackToEnv() {
    ArbigentIosRealDeviceSettings.current = ArbigentIosRealDeviceConfiguration(deviceId = null)
    assertEquals(
      "UDID-FROM-ENV",
      ArbigentIosRealDeviceSettings.resolvedDeviceId { name ->
        if (name == ArbigentIosRealDeviceSettings.ENV_DEVICE_ID) "UDID-FROM-ENV" else null
      },
    )
  }

  // --- iproxy orphan reaping -------------------------------------------------------------

  @Test
  fun orphanIproxyPids_keepsOnlyIproxyProcesses() {
    val pids = IosRealXCTestPortForwarder.orphanIproxyPids(
      lsofOutput = "111\n222\n\n333\n",
      commandNameOf = { pid -> if (pid == "222") "python" else "iproxy" },
    )
    assertEquals(listOf("111", "333"), pids)
  }

  @Test
  fun orphanIproxyPids_ignoresNonNumericLines() {
    val pids = IosRealXCTestPortForwarder.orphanIproxyPids(
      lsofOutput = "COMMAND\n555\n",
      commandNameOf = { "iproxy" },
    )
    assertEquals(listOf("555"), pids)
  }

  // --- devicectl controller --------------------------------------------------------------

  @Test
  fun launch_terminatesExistingAndTargetsCoreDevice() {
    val executor = FakeExecutor()
    val controller = ArbigentDevicectlIOSDevice(
      coreDeviceIdentifier = "CORE-ID-1",
      executor = executor,
      delegate = NoopIOSDevice(),
    )
    controller.launch("com.apple.Preferences", emptyMap())
    val cmd = executor.commands.single()
    assertEquals(
      listOf(
        "xcrun", "devicectl", "device", "process", "launch",
        "--terminate-existing", "--device", "CORE-ID-1", "com.apple.Preferences",
      ),
      cmd,
    )
  }

  @Test
  fun launch_failsWithMessageOnNonZeroExit() {
    val executor = FakeExecutor(
      responses = mapOf("xcrun devicectl" to ArbigentCommandResult(1, "", "no such device")),
    )
    val controller = ArbigentDevicectlIOSDevice("CORE-ID-1", executor, NoopIOSDevice())
    val e = assertFailsWith<IllegalStateException> { controller.launch("com.example", emptyMap()) }
    assertTrue(e.message!!.contains("no such device"))
  }

  /** Minimal IOSDevice delegate so the controller can be built without maestro's stub. */
  private class NoopIOSDevice : IOSDevice {
    override val deviceId: String get() = "noop"
    override fun open() {}
    override fun deviceInfo() = throw UnsupportedOperationException()
    override fun viewHierarchy(excludeKeyboardElements: Boolean) = throw UnsupportedOperationException()
    override fun tap(x: Int, y: Int) {}
    override fun longPress(x: Int, y: Int, durationMs: Long) {}
    override fun scroll(xStart: Double, yStart: Double, xEnd: Double, yEnd: Double, duration: Double) {}
    override fun input(text: String) {}
    override fun install(stream: java.io.InputStream) {}
    override fun uninstall(id: String) {}
    override fun clearAppState(id: String) {}
    override fun clearKeychain() = com.github.michaelbull.result.Ok(Unit)
    override fun launch(id: String, launchArguments: Map<String, Any>) {}
    override fun stop(id: String) {}
    override fun isKeyboardVisible() = false
    override fun openLink(link: String) = com.github.michaelbull.result.Ok(Unit)
    override fun takeScreenshot(out: okio.Sink, compressed: Boolean) {}
    override fun startScreenRecording(out: okio.Sink) = throw UnsupportedOperationException()
    override fun setLocation(latitude: Double, longitude: Double) = com.github.michaelbull.result.Ok(Unit)
    override fun setOrientation(orientation: String) {}
    override fun isShutdown() = false
    override fun isScreenStatic() = false
    override fun setPermissions(id: String, permissions: Map<String, String>) {}
    override fun pressKey(name: String) {}
    override fun pressButton(name: String) {}
    override fun eraseText(charactersToErase: Int) {}
    override fun addMedia(path: String) {}
    override fun close() {}
  }
}
