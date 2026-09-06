package io.github.takahirom.arbigent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File

/**
 * A replay log that cannot be replayed, with the reason. Thrown while reading rather than returned,
 * because every caller has to stop: half of a log replays to a screen the scenario never reached.
 */
@ArbigentInternalApi
public class ArbigentReplayLogException(
  message: String,
  cause: Throwable? = null,
) : Exception(message, cause)

/** One step of a replay log, with the device events it sent, in the order they were sent. */
@ArbigentInternalApi
public data class ArbigentReplayLogStep(
  /** Globally numbered across tasks, from 1. Zero means a task's `init` phase. */
  public val number: Int,
  public val taskIndex: Int,
  public val isInit: Boolean,
  public val action: String,
  public val log: String,
  public val memo: String?,
  public val goal: String,
  /** What the recorded decision was looking at. A step with no [target] waits for one of these. */
  public val screen: List<ArbigentElementIdentity>,
  public val target: ArbigentReplayLogTarget?,
  public val timestamp: Long,
  public val events: List<ArbigentDeviceEvent>,
) {
  /** What to print for this step: the recorded log line, else the action name, else "setup". */
  public fun label(): String = when {
    isInit -> "setup"
    log.isNotBlank() -> log
    action.isNotBlank() -> action
    else -> "(no action recorded)"
  }
}

/** The element a recorded step acted on, and where it was at the time. */
@ArbigentInternalApi
public data class ArbigentReplayLogTarget(
  public val identity: ArbigentElementIdentity,
  public val centerX: Int? = null,
  public val centerY: Int? = null,
)

/**
 * One successful scenario run, read back from the jsonl log
 * [ArbigentReplayScriptWriter] wrote.
 */
@ArbigentInternalApi
public data class ArbigentReplayLog(
  public val scenarioId: String,
  public val schemaVersion: Int,
  public val platform: ArbigentDeviceOs,
  public val goal: String,
  public val appId: String?,
  /** The device's size while recording, when it reported one; used to replay taps proportionally. */
  public val screenWidth: Int?,
  public val screenHeight: Int?,
  /** Resource ids the last task left on screen. Advisory: what "arrived" is checked against. */
  public val signature: List<String>,
  public val steps: List<ArbigentReplayLogStep>,
) {
  /** The highest numbered (non-init) step, or null when the log holds only setup blocks. */
  public fun lastStepNumber(): Int? = steps.filter { !it.isInit }.maxOfOrNull { it.number }

  /**
   * The steps a range asks for, with the setup blocks that belong to them.
   *
   * A setup block rides with the step that came after it: a relaunch in the middle of a run is only
   * needed when the step it prepared for is replayed. The block before the first step is the
   * exception and is always included with [withInit], because it is how the app gets launched at
   * all. A block at the very end rides with the step before it. [step] follows the same rule, so
   * `--step N --with-init` launches the app and replays the setup that prepared step N.
   */
  public fun select(
    step: Int? = null,
    from: Int? = null,
    until: Int? = null,
    withInit: Boolean = false,
  ): List<ArbigentReplayLogStep> {
    if (step != null && step < 1) throw ArbigentReplayLogException("--step must be 1 or more but was $step")
    if (from != null && from < 1) throw ArbigentReplayLogException("--from must be 1 or more but was $from")
    if (until != null && until < 1) throw ArbigentReplayLogException("--until must be 1 or more but was $until")
    if (from != null && until != null && from > until) {
      throw ArbigentReplayLogException("--from $from is after --until $until")
    }
    val selected = mutableListOf<ArbigentReplayLogStep>()
    var pending = mutableListOf<ArbigentReplayLogStep>()
    var seenStep = false
    var lastWanted = false
    steps.forEach { candidate ->
      if (candidate.isInit) {
        if (withInit) pending += candidate
        return@forEach
      }
      lastWanted = candidate.isWanted(step, from, until)
      if (lastWanted) {
        selected += pending
        selected += candidate
      } else if (!seenStep) {
        selected += pending
      }
      pending = mutableListOf()
      seenStep = true
    }
    if (pending.isNotEmpty() && (lastWanted || !seenStep)) selected += pending
    // Setup alone is not a replay: with --with-init it would clear state, launch the app and report
    // success without ever reaching the step that was asked for.
    if (selected.none { !it.isInit }) {
      val requested = when {
        step != null -> "--step $step"
        from != null && until != null -> "--from $from --until $until"
        from != null -> "--from $from"
        until != null -> "--until $until"
        else -> "this log"
      }
      val numbers = steps.filter { !it.isInit }.map { it.number }
      throw ArbigentReplayLogException(
        if (numbers.isEmpty()) "$requested selects no step: this log records no replayable step"
        else "$requested selects no step: steps run ${numbers.min()}..${numbers.max()}",
      )
    }
    return selected
  }

  private fun ArbigentReplayLogStep.isWanted(step: Int?, from: Int?, until: Int?): Boolean {
    if (step != null && number != step) return false
    if (from != null && number < from) return false
    if (until != null && number > until) return false
    return true
  }

  @ArbigentInternalApi
  public companion object {
    private val json = Json { ignoreUnknownKeys = true }

    public fun read(file: File): ArbigentReplayLog {
      if (!file.isFile) throw ArbigentReplayLogException("${file.path} is not a file")
      // An unreadable file is as useless to a replay as an unparsable one, and the caller reports
      // both the same way, so it must not reach it as a different kind of failure.
      val text = try {
        file.readText()
      } catch (failure: Exception) {
        throw ArbigentReplayLogException("${file.path} cannot be read (${failure.message})", failure)
      }
      return parse(text, file.path)
    }

    public fun parse(text: String, source: String): ArbigentReplayLog {
      val records = text.lineSequence()
        .filter { it.isNotBlank() }
        .mapIndexed { index, line ->
          runCatching { json.parseToJsonElement(line).jsonObject }
            .getOrElse {
              throw ArbigentReplayLogException("$source line ${index + 1} is not a JSON object: $it")
            }
        }
        .toList()
      requireOneFinishedRun(records, source)
      return try {
        build(records, source)
      } catch (exception: ArbigentReplayLogException) {
        throw exception
      } catch (exception: Exception) {
        // A field of the wrong shape ("signature": {}) reaches kotlinx.serialization as a raw
        // IllegalArgumentException, which callers would report as a crash rather than as the
        // unreadable log it is.
        throw ArbigentReplayLogException("$source is not a replay log this arbigent can read: $exception")
      }
    }

    /**
     * A replay log is one finished run: a `scenario_start` first, a successful `scenario_end` last.
     *
     * Anything else (a missing or failed end, lines after the end, a second start, or lines from
     * another scenario) means the file is not the record of one successful run, and replaying part
     * of it would not be a replay.
     */
    private fun requireOneFinishedRun(records: List<JsonObject>, source: String) {
      val first = records.firstOrNull()
        ?: throw ArbigentReplayLogException("$source is empty")
      if (first.string("type") != "scenario_start") {
        throw ArbigentReplayLogException("$source does not start with a scenario_start line")
      }
      if (records.count { it.string("type") == "scenario_start" } > 1) {
        throw ArbigentReplayLogException(
          "$source has more than one scenario_start line; a replay log holds a single run",
        )
      }
      val last = records.last()
      if (last.string("type") != "scenario_end" || last.string("status") != "success") {
        throw ArbigentReplayLogException(
          "$source does not end with a successful scenario_end " +
            "(the run was cut short or failed, or lines were appended after it)",
        )
      }
      if (records.dropLast(1).any { it.string("type") == "scenario_end" }) {
        throw ArbigentReplayLogException("$source has a scenario_end line before the end of the file")
      }
      val scenarioId = first.string("task")
      records.forEach { record ->
        val task = record.string("task") ?: return@forEach
        if (task != scenarioId) {
          throw ArbigentReplayLogException("$source mixes lines from scenarios '$scenarioId' and '$task'")
        }
      }
      val schemaVersion = first.int("schemaVersion")
        ?: throw ArbigentReplayLogException("$source does not declare a schemaVersion")
      if (schemaVersion != ReplayLogSchemaVersion) {
        throw ArbigentReplayLogException(
          "$source declares schema version $schemaVersion, but this arbigent reads version " +
            "$ReplayLogSchemaVersion. Re-record the scenario with this arbigent.",
        )
      }
    }

    private fun build(records: List<JsonObject>, source: String): ArbigentReplayLog {
      val header = records.first()
      val platformName = header.string("platform")
        ?: throw ArbigentReplayLogException("$source does not declare a platform")
      val platform = ArbigentDeviceOs.entries.firstOrNull { it.name.equals(platformName, ignoreCase = true) }
        ?: throw ArbigentReplayLogException(
          "$source was recorded on platform '$platformName', which this arbigent does not know",
        )
      val steps = mutableListOf<MutableStep>()
      var previousKey: Pair<Int, Int>? = null
      var signature = emptyList<String>()
      records.forEach { record ->
        when (record.string("type")) {
          "scenario_start" -> return@forEach
          "scenario_end" -> {
            signature = record["signature"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
              ?: emptyList()
            return@forEach
          }
        }
        val number = record.int("step") ?: 0
        val taskIndex = record.int("taskIndex") ?: 0
        val key = taskIndex to number
        // Groups are consecutive runs of the same key, not one group per key: a task that fell back
        // to the AI launches the app again after the steps it had already replayed, and that second
        // setup block has to stay where it happened rather than merge into the first.
        val step = if (key != previousKey) {
          previousKey = key
          MutableStep(number = number, taskIndex = taskIndex, timestamp = record.long("ts") ?: 0L)
            .also { steps += it }
        } else {
          steps.last()
        }
        when (record.string("type")) {
          "decision" -> {
            step.action = record.string("action").orEmpty()
            step.log = record.string("log").orEmpty()
            step.memo = record.string("memo")
            step.goal = record.string("goal").orEmpty()
            step.screen = record["screen"]?.jsonArray
              ?.mapNotNull { it.jsonObject.toIdentityOrNull() }
              ?: emptyList()
            step.timestamp = record.long("ts") ?: step.timestamp
          }

          "target" -> {
            // An element with no text, resource id or accessibility id is unidentifiable rather
            // than corrupt, and only weakens the divergence check, so it is skipped, not refused.
            val identity = record.toIdentityOrNull() ?: return@forEach
            val center = record["center"]?.jsonObject
            step.target = ArbigentReplayLogTarget(
              identity = identity.copy(occurrence = record.int("occurrence") ?: 0),
              centerX = center?.int("x"),
              centerY = center?.int("y"),
            )
          }

          "device", "init" -> {
            // The writer emits `event` on every device record, so a record without one is a
            // truncated or hand-edited log. Dropping it would replay the surrounding steps with a
            // gap in the middle and still report success, so the whole log is refused instead.
            val event = record["event"] ?: throw ArbigentReplayLogException(
              "$source step $number has a ${record.string("type")} record without an event",
            )
            step.events += runCatching {
              json.decodeFromJsonElement(ArbigentDeviceEvent.serializer(), event)
            }.getOrElse {
              throw ArbigentReplayLogException(
                "$source step $number carries an event this arbigent cannot read: $it",
              )
            }
          }
        }
      }
      return ArbigentReplayLog(
        scenarioId = header.string("task").orEmpty(),
        schemaVersion = ReplayLogSchemaVersion,
        platform = platform,
        goal = header.string("goal").orEmpty(),
        appId = header.string("appId"),
        screenWidth = header.int("width")?.takeIf { it > 0 },
        screenHeight = header.int("height")?.takeIf { it > 0 },
        signature = signature,
        // A step that sent nothing to the device has nothing to replay.
        steps = steps.filter { it.events.isNotEmpty() }.map { it.toStep() },
      )
    }

    private class MutableStep(
      val number: Int,
      val taskIndex: Int,
      var timestamp: Long,
    ) {
      var action: String = ""
      var log: String = ""
      var memo: String? = null
      var goal: String = ""
      var screen: List<ArbigentElementIdentity> = emptyList()
      var target: ArbigentReplayLogTarget? = null
      val events: MutableList<ArbigentDeviceEvent> = mutableListOf()

      fun toStep(): ArbigentReplayLogStep = ArbigentReplayLogStep(
        number = number,
        taskIndex = taskIndex,
        isInit = number == 0,
        action = action,
        log = log,
        memo = memo,
        goal = goal,
        screen = screen,
        target = target,
        timestamp = timestamp,
        events = events.toList(),
      )
    }

    private fun JsonObject.string(key: String): String? =
      this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
      runCatching { this[key]?.jsonPrimitive?.int }.getOrNull()

    private fun JsonObject.long(key: String): Long? =
      runCatching { this[key]?.jsonPrimitive?.long }.getOrNull()

    /**
     * The identity a `target` line or a `screen` hint names, or null when it names nothing an
     * element can be looked up by. [ArbigentElementIdentity] refuses to be built from no
     * attributes, so an empty hint is dropped rather than allowed to throw here.
     */
    private fun JsonObject.toIdentityOrNull(): ArbigentElementIdentity? {
      val text = string("text")?.takeIf { it.isNotEmpty() }
      val resourceId = string("resourceId")?.takeIf { it.isNotEmpty() }
      val accessibilityId = string("accessibilityId")?.takeIf { it.isNotEmpty() }
      if (text == null && resourceId == null && accessibilityId == null) return null
      return ArbigentElementIdentity(
        text = text,
        resourceId = resourceId,
        accessibilityId = accessibilityId,
      )
    }
  }
}
