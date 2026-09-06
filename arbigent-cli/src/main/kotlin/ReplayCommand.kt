@file:OptIn(ArbigentInternalApi::class)

package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.types.int
import io.github.takahirom.arbigent.ArbigentInternalApi
import io.github.takahirom.arbigent.ArbigentReplayLog
import io.github.takahirom.arbigent.ArbigentReplayLogException
import io.github.takahirom.arbigent.ArbigentReplayRunner
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Replays a recorded scenario log against a device without asking an AI anything.
 *
 * This is how a person or another tool gets to a screen a scenario already reached: the log holds
 * the device events of a successful run, so replaying them is both cheaper and more repeatable than
 * asking the agent to find its way there again. No API key is needed, because no model is called.
 *
 * The exit code says what happened, so a caller can branch on it: 0 replayed and arrived, 1 the log
 * or the range cannot be replayed at all, 2 the screen was not the one the recording acted on, 3 the
 * device could not be driven.
 */
class ArbigentReplayCommand : CliktCommand(name = "replay") {
  override fun help(context: com.github.ajalt.clikt.core.Context): String =
    "Replay a recorded scenario log on a device, without using AI"

  private val logPath by argument(
    name = "log",
    help = "Path to the .jsonl replay log written for a successful scenario",
  )

  private val step by defaultOption(
    "--step",
    help = "Replay only this step number",
  ).int()

  private val from by defaultOption(
    "--from",
    help = "Start at this step number",
  ).int()

  private val until by defaultOption(
    "--until",
    help = "Stop after this step number",
  ).int()

  private val withInit by defaultOption(
    "--with-init",
    help = "Also replay the setup blocks (app launch, state reset) that prepared the chosen steps",
  ).flag()

  private val noWait by defaultOption(
    "--no-wait",
    help = "Do not wait for each step's target or screen hints, just send the events",
  ).flag()

  private val show by defaultOption(
    "--show",
    help = "Print what would be replayed and exit, without connecting to a device",
  ).flag()

  internal val iosAppleTeamId by defaultOption(
    "--ios-xctest-apple-team-id",
    help = "Apple developer team id used to sign the XCTest runner for a physical iPhone.",
  )

  internal val iosRealDeviceId by defaultOption(
    "--ios-real-device-id",
    help = "Hardware UDID selecting a specific physical iPhone.",
  )

  internal val iosRealDevicePort by defaultOption(
    "--ios-real-device-port",
    help = "Local port used to reach the XCTest runner on a physical iPhone.",
  )

  override fun run() {
    val log = try {
      ArbigentReplayLog.read(File(logPath))
    } catch (exception: ArbigentReplayLogException) {
      throw CliktError(exception.message ?: "the replay log cannot be read")
    }
    val selected = try {
      log.select(step = step, from = from, until = until, withInit = withInit)
    } catch (exception: ArbigentReplayLogException) {
      throw CliktError(exception.message ?: "the given range cannot be replayed")
    }
    if (show) {
      // Built up and echoed rather than streamed, so the listing goes through Clikt's own output.
      val listing = StringBuilder()
      ArbigentReplayRunner.show(log, selected, listing)
      echo(listing.toString().trimEnd())
      return
    }
    // Neither an empty range nor an unreproducible command needs a device to be refused, so they
    // are checked before a driver is started.
    ArbigentReplayRunner.verifySelection(selected)?.let { reason -> throw CliktError(reason) }
    // The log says which kind of device it was recorded on, and a log only replays on that kind, so
    // the platform is read from the log rather than asked for again.
    val resolvedIosRealDevicePort = parseIosRealDevicePort(iosRealDevicePort)
    val device = try {
      connectDevice(
        os = log.platform.name.lowercase(),
        iosAppleTeamId = iosAppleTeamId,
        iosRealDeviceId = iosRealDeviceId,
        iosRealDevicePort = resolvedIosRealDevicePort,
      )
    } catch (exception: Exception) {
      // No device to drive is a device failure, not a broken log: the caller can retry the same
      // command once a device is attached.
      System.err.println("could not connect to a ${log.platform.name.lowercase()} device: $exception")
      throw ProgramResult(ArbigentReplayRunner.EXIT_DEVICE)
    }
    try {
      val runner = ArbigentReplayRunner(
        log = log,
        device = device,
        out = System.out,
        err = System.err,
        waitForScreens = !noWait,
      )
      runner.verifyReplayable(selected)?.let { reason -> throw CliktError(reason) }
      val exitCode = runBlocking { runner.run(selected) }
      if (exitCode != ArbigentReplayRunner.EXIT_OK) throw ProgramResult(exitCode)
    } finally {
      device.close()
    }
  }
}
