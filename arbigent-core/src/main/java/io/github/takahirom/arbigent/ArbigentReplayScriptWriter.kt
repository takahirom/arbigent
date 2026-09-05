package io.github.takahirom.arbigent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * One recorded step of a scenario, after task-local recordings have been flattened and numbered.
 *
 * Every artifact written for a run is rendered from this shape, so they cannot disagree about which
 * step is which.
 */
internal data class ArbigentReplayScriptStep(
  val taskIndex: Int,
  /** Globally numbered across tasks, from 1. Zero means the task's `init` phase. */
  val number: Int,
  val isInit: Boolean,
  val actionName: String?,
  val actionLog: String?,
  val memo: String?,
  val screenshot: String?,
  val target: ArbigentElementIdentity?,
  val targetBounds: ArbigentReplayScriptRecorder.RecordedBounds?,
  val screen: List<ArbigentElementIdentity>,
  val events: List<ArbigentDeviceEvent>,
  val timestamp: Long,
)

/**
 * Writes the replay event log for one successful scenario.
 *
 * Only successful runs are written. A failed scenario leaves whatever was written before untouched,
 * because a half-finished log replays to a screen the scenario never reached, which is worse than
 * a stale one whose scenario id says what it is.
 */
internal class ArbigentReplayScriptWriter(
  private val outputDir: File,
) {
  fun write(
    scenarioId: String,
    goals: List<String>,
    tasks: List<ArbigentReplayScriptRecorder.RecordedTask>,
    signature: List<String>,
    screenWidth: Int = 0,
    screenHeight: Int = 0,
  ) {
    val steps = flatten(tasks)
    if (steps.isEmpty()) {
      arbigentInfoLog("Not writing a replay script for scenario $scenarioId: the run recorded no device events")
      return
    }
    outputDir.mkdirs()
    val baseName = fileBaseName(scenarioId)
    val logFile = File(outputDir, "$baseName.jsonl")
    writeAtomically(
      logFile,
      jsonLines(scenarioId, goals, tasks, steps, signature, screenWidth, screenHeight)
        .joinToString(separator = "\n", postfix = "\n"),
    )
    arbigentInfoLog("Wrote replay script for scenario $scenarioId to ${logFile.absolutePath}")
  }

  private fun flatten(
    tasks: List<ArbigentReplayScriptRecorder.RecordedTask>,
  ): List<ArbigentReplayScriptStep> {
    var number = 0
    return tasks.flatMap { task ->
      task.steps.mapNotNull { step ->
        // A step that sent nothing to the device (a memo, a goal-achieved decision) has nothing to
        // replay, and numbering it would make the step numbers in the log skip.
        if (step.events.isEmpty()) return@mapNotNull null
        if (!step.isInit) number++
        ArbigentReplayScriptStep(
          taskIndex = task.taskIndex,
          number = if (step.isInit) 0 else number,
          isInit = step.isInit,
          actionName = step.actionName,
          actionLog = step.actionLog,
          memo = step.memo,
          screenshot = step.screenshot,
          target = step.target,
          targetBounds = step.targetBounds,
          screen = step.screen,
          events = step.events.toList(),
          timestamp = step.timestamp,
        )
      }
    }
  }

  private fun jsonLines(
    scenarioId: String,
    goals: List<String>,
    tasks: List<ArbigentReplayScriptRecorder.RecordedTask>,
    steps: List<ArbigentReplayScriptStep>,
    signature: List<String>,
    screenWidth: Int,
    screenHeight: Int,
  ): List<String> {
    val lines = mutableListOf<JsonObject>()
    val firstTimestamp = steps.first().timestamp
    val appId = steps.asSequence()
      .flatMap { it.events.asSequence() }
      .filterIsInstance<ArbigentDeviceEvent.LaunchApp>()
      .firstOrNull()?.appId
    lines += buildJsonObject {
      header("scenario_start", scenarioId, taskIndex = 0, step = 0, ts = firstTimestamp)
      put("goal", goals.joinToString(separator = " -> "))
      appId?.let { put("appId", it) }
      // Only when the device reported one; a zero would read as a real screen size.
      if (screenWidth > 0 && screenHeight > 0) {
        put("width", screenWidth)
        put("height", screenHeight)
      }
    }
    steps.forEach { step ->
      if (step.isInit) {
        step.events.forEach { event ->
          lines += buildJsonObject {
            header("init", scenarioId, step.taskIndex, step.number, event.timestamp)
            put("event", json.encodeToJsonElement(ArbigentDeviceEvent.serializer(), event))
          }
        }
        return@forEach
      }
      lines += buildJsonObject {
        header("decision", scenarioId, step.taskIndex, step.number, step.timestamp)
        put("action", step.actionName ?: "")
        put("log", step.actionLog ?: "")
        put("goal", tasks.firstOrNull { it.taskIndex == step.taskIndex }?.goal ?: "")
        step.memo?.let { put("memo", it) }
        step.screenshot?.let { put("screenshot", it) }
        // What the decision was looking at. A step without a target waits for one of these.
        if (step.screen.isNotEmpty()) {
          put("screen", buildJsonArray {
            step.screen.forEach { identity ->
              add(buildJsonObject {
                identity.text?.let { put("text", it) }
                identity.resourceId?.let { put("resourceId", it) }
                identity.accessibilityId?.let { put("accessibilityId", it) }
              })
            }
          })
        }
      }
      step.target?.let { target ->
        lines += buildJsonObject {
          header("target", scenarioId, step.taskIndex, step.number, step.timestamp)
          target.text?.let { put("text", it) }
          target.resourceId?.let { put("resourceId", it) }
          target.accessibilityId?.let { put("accessibilityId", it) }
          put("occurrence", target.occurrence)
          // Maestro sees attributes that an adb-side dump may not, so the geometry travels with
          // the identity as a coordinate fallback.
          step.targetBounds?.let { bounds ->
            put("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
            put("center", buildJsonObject {
              put("x", bounds.centerX)
              put("y", bounds.centerY)
            })
          }
        }
      }
      step.events.forEach { event ->
        lines += buildJsonObject {
          header("device", scenarioId, step.taskIndex, step.number, event.timestamp)
          put("event", json.encodeToJsonElement(ArbigentDeviceEvent.serializer(), event))
        }
      }
    }
    val last = steps.last()
    lines += buildJsonObject {
      header("scenario_end", scenarioId, last.taskIndex, last.number, last.timestamp)
      put("status", "success")
      put("signature", buildJsonArray { signature.forEach { add(it) } })
    }
    return lines.map { it.toString() }
  }

  internal companion object {
    private val json = Json { encodeDefaults = true }

    /**
     * Scenario ids are free text and become file names, so anything that is not a letter, digit,
     * underscore or hyphen is replaced rather than escaped.
     */
    fun sanitizeFileName(scenarioId: String): String =
      scenarioId.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
        // A name that starts with a dash reads as an option on the runner's command line.
        .replaceFirst(Regex("^-"), "_")

    /**
     * The file name a scenario's script is written under. Two ids that sanitize to the same string
     * (`a/b` and `a b`) would otherwise overwrite each other, so a name the sanitizer had to change
     * carries a short hash of the original id.
     */
    fun fileBaseName(scenarioId: String): String {
      val sanitized = sanitizeFileName(scenarioId)
      if (sanitized == scenarioId) return sanitized
      val hash = java.security.MessageDigest.getInstance("SHA-256")
        .digest(scenarioId.toByteArray())
        .take(3)
        .joinToString("") { "%02x".format(it) }
      return "$sanitized-$hash"
    }

    /**
     * Writes through a sibling temp file and renames, so a reader (a CI upload, a runner started
     * on the previous script) never sees a half-written file.
     */
    fun writeAtomically(target: File, text: String) {
      val temp = File(target.parentFile, "${target.name}.tmp")
      temp.writeText(text)
      try {
        java.nio.file.Files.move(
          temp.toPath(), target.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
      } catch (e: java.nio.file.AtomicMoveNotSupportedException) {
        java.nio.file.Files.move(
          temp.toPath(), target.toPath(),
          java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
      }
    }
  }
}

private fun kotlinx.serialization.json.JsonObjectBuilder.header(
  type: String,
  scenarioId: String,
  taskIndex: Int,
  step: Int,
  ts: Long,
) {
  put("type", type)
  put("task", scenarioId)
  put("taskIndex", taskIndex)
  put("step", step)
  put("ts", ts)
}
