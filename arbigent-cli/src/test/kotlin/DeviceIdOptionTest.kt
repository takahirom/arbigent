@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.sources.ChainedValueSource
import com.github.ajalt.clikt.sources.ValueSource
import com.github.ajalt.clikt.testing.test
import io.github.takahirom.arbigent.ArbigentDevice
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.ArbigentIosRealDeviceSettings
import io.github.takahirom.arbigent.ArbigentRequestedDevice
import io.github.takahirom.arbigent.ENV_ARBIGENT_DEVICE_ID
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the two layers a pure selection test cannot reach: how `--device-id` is resolved from the
 * command line, the environment and the settings file, and whether the resolved value actually
 * reaches device connection from both `run` and `run task`.
 */
class DeviceIdOptionTest {
  private val yaml = File("build/arbigent/device-id-project.yaml")
  private val settingsFile = File("build/test-.arbigent/settings-device-id.yml")

  @BeforeTest
  fun setup() {
    yaml.parentFile.mkdirs()
    yaml.writeText(
      """
scenarios:
- id: "open-settings"
  goal: "Open the OS settings screen"
      """.trimIndent()
    )
    settingsFile.parentFile.mkdirs()
  }

  @AfterTest
  fun tearDown() {
    settingsFile.delete()
  }

  /** Records what the command handed to connection instead of touching a device. */
  private class RecordingConnector : ArbigentDeviceConnector {
    var requestedDevice: ArbigentRequestedDevice? = null
    var os: String? = null
    var called = false
    override fun connect(
      os: String,
      requestedDevice: ArbigentRequestedDevice?,
      iosAppleTeamId: String?,
      iosRealDevicePort: Int?,
    ): ArbigentDevice {
      called = true
      this.os = os
      this.requestedDevice = requestedDevice
      // Stops the run right here: everything after connection needs a real device and an AI.
      throw ConnectorReached()
    }
  }

  private class ConnectorReached : RuntimeException("connector reached")

  /** The recording connector aborts the run, so every wiring test expects that exact escape. */
  private fun reachesConnector(block: () -> Unit) {
    assertFailsWith<ConnectorReached> { block() }
  }

  private fun cli(runCommand: ArbigentRunCommand, useSettingsFile: Boolean = true) =
    ArbigentCli().apply {
      if (useSettingsFile) {
        context {
          valueSource = ChainedValueSource(
            listOf(
              YamlValueSource.from(settingsFile.absolutePath, getKey = ValueSource.getKey(joinSubcommands = ".")),
              YamlValueSource.from(settingsFile.absolutePath, getKey = { _, option -> option.names.first().removePrefix("--") }),
            )
          )
        }
      }
    }.subcommands(runCommand.subcommands(ArbigentRunTaskCommand()))

  private fun runCommand() = ArbigentRunCommand()

  // --- resolution and precedence ----------------------------------------------------------

  @Test
  fun `device-id resolves from the global settings key`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\ndevice-id: emulator-5554\n")
    val run = runCommand()

    val test = cli(run).test("run --dry-run", envvars = mapOf("OPENAI_API_KEY" to "key"))

    assertContains(test.output, "Selected scenarios for execution")
    assertEquals("emulator-5554", run.deviceId)
  }

  @Test
  fun `device-id resolves from the command-specific run key`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\nrun:\n  device-id: emulator-5556\n")
    val run = runCommand()

    val test = cli(run).test("run --dry-run", envvars = mapOf("OPENAI_API_KEY" to "key"))

    assertContains(test.output, "Selected scenarios for execution")
    assertEquals("emulator-5556", run.deviceId)
  }

  @Test
  fun `the environment variable beats the settings file`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\ndevice-id: emulator-5554\n")
    val run = runCommand()

    cli(run).test(
      "run --dry-run",
      envvars = mapOf("OPENAI_API_KEY" to "key", ENV_ARBIGENT_DEVICE_ID to "emulator-5558"),
    )

    assertEquals("emulator-5558", run.deviceId)
  }

  @Test
  fun `the command line beats the environment variable`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\n")
    val run = runCommand()

    cli(run).test(
      "run --dry-run --device-id=emulator-5560",
      envvars = mapOf("OPENAI_API_KEY" to "key", ENV_ARBIGENT_DEVICE_ID to "emulator-5558"),
    )

    assertEquals("emulator-5560", run.deviceId)
  }

  // Blank must not degrade to "unset": that would auto-select, which is what an explicit request asks
  // us not to do.
  @Test
  fun `an empty device-id is a configuration error`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\n")

    val test = cli(runCommand()).test(
      listOf("run", "--dry-run", "--device-id", ""),
      envvars = mapOf("OPENAI_API_KEY" to "key"),
    )

    assertEquals(1, test.statusCode)
    assertContains(test.stderr, "--device-id must not be empty")
  }

  // --- migration off the iOS-only option ---------------------------------------------------

  @Test
  fun `the superseded settings key is reported rather than ignored`() {
    settingsFile.writeText(
      "project-file: ${yaml.absolutePath}\nios-real-device-id: 00008110-XXXXXXXXXXXXXXXX\n"
    )

    val test = cli(runCommand()).test("run --dry-run --os=ios", envvars = mapOf("OPENAI_API_KEY" to "key"))

    assertEquals(1, test.statusCode)
    assertContains(test.stderr, "--device-id")
  }

  @Test
  fun `the superseded environment variable fails an ios run`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\n")

    val test = cli(runCommand()).test(
      "run --dry-run --os=ios",
      envvars = mapOf(
        "OPENAI_API_KEY" to "key",
        ArbigentIosRealDeviceSettings.LEGACY_ENV_DEVICE_ID to "00008110-XXXXXXXXXXXXXXXX",
      ),
    )

    assertEquals(1, test.statusCode)
    assertContains(test.stderr, ENV_ARBIGENT_DEVICE_ID)
  }

  // An exported variable from an old iOS setup must not break unrelated Android runs.
  @Test
  fun `the superseded environment variable does not break an android run`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\n")

    val test = cli(runCommand()).test(
      "run --dry-run",
      envvars = mapOf(
        "OPENAI_API_KEY" to "key",
        ArbigentIosRealDeviceSettings.LEGACY_ENV_DEVICE_ID to "00008110-XXXXXXXXXXXXXXXX",
      ),
    )

    assertEquals(0, test.statusCode)
    assertContains(test.output, "Selected scenarios for execution")
  }

  // clikt does not hand a parent's option to a subcommand, so this would otherwise be accepted and
  // then silently ignored.
  @Test
  fun `device-id before the task subcommand is rejected`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\n")

    val test = cli(runCommand()).test(
      listOf("run", "--device-id", "emulator-5554", "task", "open the settings screen"),
      envvars = mapOf("OPENAI_API_KEY" to "key"),
    )

    assertEquals(1, test.statusCode)
    assertContains(test.stderr, "after `task`")
  }

  // The rejection used to be derived by comparing the resolved value with the settings file, so a
  // flag that matched `run.device-id` was mistaken for a settings value and slipped through — and
  // the run then connected to whatever `task` resolved on its own (here the global key).
  @Test
  fun `device-id before the task subcommand is rejected even when a settings key holds the same value`() {
    settingsFile.writeText(
      "project-file: ${yaml.absolutePath}\ndevice-id: emulator-5556\nrun:\n  device-id: emulator-5554\n"
    )
    val connector = RecordingConnector()
    val run = ArbigentRunCommand()
    val cli = ArbigentCli().apply {
      context {
        valueSource = ChainedValueSource(
          listOf(
            YamlValueSource.from(settingsFile.absolutePath, getKey = ValueSource.getKey(joinSubcommands = ".")),
            YamlValueSource.from(settingsFile.absolutePath, getKey = { _, option -> option.names.first().removePrefix("--") }),
          )
        )
      }
    }.subcommands(run.subcommands(ArbigentRunTaskCommand(deviceConnector = connector)))

    val test = cli.test(
      listOf("run", "--device-id", "emulator-5554", "task", "open the settings screen"),
      envvars = mapOf("OPENAI_API_KEY" to "key"),
    )

    assertEquals(1, test.statusCode)
    assertContains(test.stderr, "after `task`")
    // The point of the rejection: no other device may be connected to instead.
    assertTrue(!connector.called, "connected to ${connector.requestedDevice?.id}")
  }

  // The parent must not validate a value it does not own: `task` resolves its own options, where a
  // flag beats the environment, so an exported-but-empty variable is irrelevant there.
  @Test
  fun `an empty environment variable does not block a device-id passed to task`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\n")
    val connector = RecordingConnector()
    val run = ArbigentRunCommand()
    val cli = ArbigentCli().subcommands(
      run.subcommands(ArbigentRunTaskCommand(deviceConnector = connector))
    )

    reachesConnector {
      cli.test(
        listOf("run", "task", "--device-id", "emulator-5554", "open the settings screen"),
        envvars = mapOf("OPENAI_API_KEY" to "key", ENV_ARBIGENT_DEVICE_ID to ""),
      )
    }

    assertEquals("emulator-5554", connector.requestedDevice?.id)
    assertEquals("--device-id", connector.requestedDevice?.source)
  }

  // --- the value actually reaches connection ------------------------------------------------

  @Test
  fun `run passes the requested device to connection`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\n")
    val connector = RecordingConnector()
    val run = ArbigentRunCommand(deviceConnector = connector)

    reachesConnector {
      cli(run).test(
        "run --device-id=emulator-5554 --scenario-ids=open-settings",
        envvars = mapOf("OPENAI_API_KEY" to "key"),
      )
    }

    assertTrue(connector.called)
    assertEquals("emulator-5554", connector.requestedDevice?.id)
    assertEquals("--device-id", connector.requestedDevice?.source)
  }

  @Test
  fun `run task passes the requested device to connection`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\n")
    val connector = RecordingConnector()
    val run = ArbigentRunCommand()
    val cli = ArbigentCli().subcommands(
      run.subcommands(ArbigentRunTaskCommand(deviceConnector = connector))
    )

    reachesConnector {
      cli.test(
        listOf("run", "task", "--device-id", "emulator-5556", "open the settings screen"),
        envvars = mapOf("OPENAI_API_KEY" to "key"),
      )
    }

    assertTrue(connector.called)
    assertEquals("emulator-5556", connector.requestedDevice?.id)
  }

  @Test
  fun `run reports the environment variable as the source`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\n")
    val connector = RecordingConnector()
    val run = ArbigentRunCommand(deviceConnector = connector)

    reachesConnector {
      cli(run).test(
        "run --scenario-ids=open-settings",
        envvars = mapOf("OPENAI_API_KEY" to "key", ENV_ARBIGENT_DEVICE_ID to "emulator-5558"),
      )
    }

    assertEquals("emulator-5558", connector.requestedDevice?.id)
    assertEquals(ENV_ARBIGENT_DEVICE_ID, connector.requestedDevice?.source)
  }

  @Test
  fun `run reports the settings file as the source`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\ndevice-id: emulator-5554\n")
    val connector = RecordingConnector()
    val run = ArbigentRunCommand(deviceConnector = connector)

    reachesConnector {
      cli(run).test("run --scenario-ids=open-settings", envvars = mapOf("OPENAI_API_KEY" to "key"))
    }

    assertEquals("emulator-5554", connector.requestedDevice?.id)
    assertContains(connector.requestedDevice?.source ?: "", "settings")
  }

  @Test
  fun `without device-id the run auto-selects`() {
    settingsFile.writeText("project-file: ${yaml.absolutePath}\n")
    val connector = RecordingConnector()
    val run = ArbigentRunCommand(deviceConnector = connector)

    reachesConnector {
      cli(run).test("run --scenario-ids=open-settings", envvars = mapOf("OPENAI_API_KEY" to "key"))
    }

    assertTrue(connector.called)
    assertNull(connector.requestedDevice)
  }

  // --help is printed in CI logs, and on iOS this key can hold a physical iPhone's hardware UDID.
  @Test
  fun `device-id is masked in help output`() {
    assertTrue(isSensitiveOptionKey("device-id"))
  }
}
