package io.github.takahirom.arbigent

/**
 * Renders the human- and agent-readable summary that sits beside the event log.
 *
 * The log says exactly what was sent; this says what each step was trying to do and what was on
 * screen when it did. A reader who has only `adb` can follow it by hand, and an agent given the
 * file can tell whether the screen it is looking at is the one the step expected.
 */
internal fun renderReplayScriptMarkdown(
  scenarioId: String,
  baseName: String,
  goals: List<String>,
  tasks: List<ArbigentReplayScriptRecorder.RecordedTask>,
  steps: List<ArbigentReplayScriptStep>,
  signature: List<String>,
): String = buildString {
  appendLine("# $scenarioId")
  appendLine()
  appendLine("## Goal")
  appendLine()
  if (goals.size == 1) {
    appendLine(goals.single())
  } else {
    goals.forEachIndexed { index, goal -> appendLine("${index + 1}. $goal") }
  }
  appendLine()
  appendLine("## Steps")
  appendLine()
  var lastTaskIndex = -1
  steps.forEach { step ->
    if (goals.size > 1 && step.taskIndex != lastTaskIndex) {
      lastTaskIndex = step.taskIndex
      appendLine("### Task ${step.taskIndex + 1}: ${tasks.firstOrNull { it.taskIndex == step.taskIndex }?.goal.orEmpty()}")
      appendLine()
    }
    if (step.isInit) {
      appendLine("0. setup")
    } else {
      appendLine("${step.number}. ${step.actionLog ?: step.actionName ?: "step"}")
    }
    step.target?.let { appendLine("   - target: ${it.description()}") }
    // Maestro sees attributes an adb-side dump may miss, so the coordinates are spelled out for a
    // reader who has to tap by hand.
    step.targetBounds?.let {
      appendLine("   - center: (${it.centerX}, ${it.centerY}), bounds: [${it.left},${it.top}][${it.right},${it.bottom}]")
    }
    if (step.screen.isNotEmpty()) {
      appendLine("   - screen: ${step.screen.joinToString(", ") { it.label() }}")
    }
    step.memo?.takeIf { it.isNotBlank() }?.let { appendLine("   - memo: ${it.replace("\n", " ")}") }
    appendLine("   - device: ${summarizeEvents(step.events)}")
    step.target?.let { target ->
      val fallback = step.targetBounds?.let { " (or tap ${it.centerX},${it.centerY})" }.orEmpty()
      appendLine("   - wait for: ${target.description()}$fallback")
    }
    // The exact command for this one step, so an agent driving the app a step at a time can copy it
    // instead of working the number out from the option list.
    if (!step.isInit) {
      appendLine("   - replay: `./arbigentw replay $baseName.jsonl --step ${step.number}`")
    }
    appendLine()
  }
  if (signature.isNotEmpty()) {
    appendLine("## Expected end state")
    appendLine()
    appendLine("expect: resource ids ${signature.joinToString(", ")}")
    appendLine()
  }
  appendLine("## Replay")
  appendLine()
  appendLine("From a fresh device, setup included:")
  appendLine()
  appendLine("```")
  appendLine("./arbigentw replay $baseName.jsonl --with-init")
  appendLine("```")
  appendLine()
  appendLine("With the app already on the screen the recording started from:")
  appendLine()
  appendLine("```")
  appendLine("./arbigentw replay $baseName.jsonl")
  appendLine("```")
  appendLine()
  appendLine("Options:")
  appendLine()
  appendLine("- `--show` prints the steps without touching a device.")
  appendLine("- `--step N` replays a single step, `--from N` / `--until N` a range.")
  appendLine("- `--with-init` also replays the setup phase (app launch, state clear).")
  appendLine("- `--no-wait` skips waiting for targets and screen hints.")
  appendLine()
  appendLine("`./arbigentw` downloads the matching arbigent on first use; `arbigent replay` is the")
  appendLine("same command for an arbigent that is already installed.")
}

/** The identity without its occurrence, which means nothing for a screen-level hint. */
private fun ArbigentElementIdentity.label(): String = listOfNotNull(
  text?.let { "text='${it.escapedForSingleQuotedOneLine()}'" },
  resourceId?.let { "resourceId='${it.escapedForSingleQuotedOneLine()}'" },
  accessibilityId?.let { "accessibilityId='${it.escapedForSingleQuotedOneLine()}'" },
).joinToString(", ")

/** `KEYCODE_DPAD_DOWN x3, tap(120,340)`: consecutive identical events collapse into a count. */
private fun summarizeEvents(events: List<ArbigentDeviceEvent>): String {
  if (events.isEmpty()) return "none"
  val parts = mutableListOf<Pair<String, Int>>()
  events.forEach { event ->
    val label = event.describe()
    val last = parts.lastOrNull()
    if (last != null && last.first == label) {
      parts[parts.size - 1] = label to last.second + 1
    } else {
      parts += label to 1
    }
  }
  return parts.joinToString(", ") { (label, count) -> if (count > 1) "$label x$count" else label }
}

private fun ArbigentDeviceEvent.describe(): String = when (this) {
  is ArbigentDeviceEvent.Tap -> "tap($x,$y)"
  is ArbigentDeviceEvent.TapElement -> buildString {
    append("tap(")
    val parts = listOfNotNull(
      textRegex?.let { "text='${it.escapedForSingleQuotedOneLine()}'" },
      idRegex?.let { "id='${it.escapedForSingleQuotedOneLine()}'" },
      // Only a later match is worth naming: index 0 and no index both read as the first one.
      index?.takeIf { it > 0 }?.let { "index=$it" },
    )
    append(parts.joinToString(", ").ifEmpty { "element" })
    append(')')
  }
  is ArbigentDeviceEvent.KeyPress -> keyName
  is ArbigentDeviceEvent.InputText -> "text(\"${text.escapedForOneLine().replace("\"", "\\\"")}\")"
  is ArbigentDeviceEvent.Swipe -> "swipe($startX,$startY -> $endX,$endY, ${durationMs}ms)"
  is ArbigentDeviceEvent.LaunchApp -> buildString {
    append("launch(").append(appId)
    if (clearState) append(", clearState")
    if (!stopApp) append(", keepRunning")
    if (clearKeychain == true) append(", clearKeychain")
    // Only when the recording asked for something other than maestro's own default, which is what
    // a launch that recorded no permissions replays with.
    permissions?.forEach { (name, mode) -> append(", ").append(name).append('=').append(mode) }
    launchArguments.forEach { (key, value) -> append(", ").append(key).append('=').append(value.toString()) }
    append(')')
  }
  is ArbigentDeviceEvent.StopApp -> "stop($appId)"
  is ArbigentDeviceEvent.ClearState -> "clearState($appId)"
  is ArbigentDeviceEvent.Wait -> "wait(${millis}ms)"
  is ArbigentDeviceEvent.OpenLink -> "openLink($url)"
  is ArbigentDeviceEvent.Unsupported -> "unsupported($command)"
}

/**
 * The summary gives every event a line of its own, so a recorded value carrying its own newline
 * would split that line and turn the rest of the event into prose. Backslashes go first, so an
 * escape this writes cannot be mistaken for one that was already in the value.
 */
private fun String.escapedForOneLine(): String = replace("\\", "\\\\")
  .replace("\r", "\\r")
  .replace("\n", "\\n")

/**
 * A value rendered inside `'…'` also has to keep its own quotes from closing the field. `\'` is a
 * legal escape in a regex too, so a reader who copies the value out gets the same matching.
 */
private fun String.escapedForSingleQuotedOneLine(): String = escapedForOneLine().replace("'", "\\'")
