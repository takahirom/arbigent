package io.github.takahirom.arbigent.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.subcommands

/**
 * A guide topic printed by `arbigent guide <name>`.
 *
 * [name] and [description] appear in `arbigent --help` and `arbigent guide --help`;
 * [body] is Markdown written for AI agents that operate the arbigent CLI.
 * Every ```yaml fenced block in [body] must be a complete, parseable project file
 * (enforced by GuideCommandTest).
 */
data class ArbigentGuideTopic(
  val name: String,
  val description: String,
  val body: String,
)

class ArbigentGuideCommand : CliktCommand(name = "guide") {
  init {
    subcommands(arbigentGuideTopics.map { ArbigentGuideTopicCommand(it) })
  }

  override fun help(context: Context): String =
    "Print guides for AI agents working with arbigent projects"

  override fun run() = Unit
}

class ArbigentGuideTopicCommand(private val topic: ArbigentGuideTopic) :
  CliktCommand(name = topic.name) {
  override fun help(context: Context): String = topic.description

  override fun run() {
    echo(topic.body)
  }
}

/** Rendered into the root `arbigent --help` epilog so agents can discover topics without running `guide`. */
fun guideHelpEpilog(): String {
  // NEL (\u0085) is Clikt's manual line break; plain newlines and runs of spaces are
  // collapsed by the help formatter, so no column alignment is attempted here.
  return arbigentGuideTopics.joinToString(
    separator = "\u0085",
    prefix = "Guides for AI agents (print one with `arbigent guide <topic>`):\u0085",
  ) { topic ->
    "${topic.name}: ${topic.description}"
  }
}

val arbigentGuideTopics: List<ArbigentGuideTopic> = listOf(
  ArbigentGuideTopic(
    name = "writing-yaml",
    description = "How to write the project YAML: scenarios, reusable scenarios, variables, assertions",
    body = """
# Writing arbigent project YAML

A project file is a YAML file passed to commands via `--project-file` (or resolved
from `.arbigent/settings.yml`, see `arbigent guide inspecting-project`).

Top-level keys:

- `scenarios` (required): list of scenarios to run.
- `reusableScenarios` (optional): parameterized scenarios that other scenarios call.
- `fixedScenarios` (optional): named Maestro YAML snippets used by the `MaestroYaml` initialization method.
- `settings` (optional): project-wide settings (prompts, cache strategy, AI options).

IMPORTANT: unknown keys are silently ignored (the parser is not strict). A typo in a
field name does not fail the load — it just does nothing. After editing, verify with:

    arbigent scenarios --project-file=path/to/project.yaml

## Scenario fields

- `id`: string. Stable identifier used by `--scenario-ids` and `dependency`. Must not contain `/`, `#`, `@`.
- `goal`: string. Natural-language goal the AI agent tries to achieve. Be specific and
  add guardrails ("Be careful not to open other pages").
- `dependency`: id of another scenario that must run first (its steps are executed
  before this scenario's goal).
- `initializationMethods`: list of setup actions, each with a `type` field:
  - `type: "LaunchApp"` — `packageName` (required), `launchArguments` (optional map)
  - `type: "CleanupData"` — `packageName` (required); clears app data
  - `type: "Back"` — `times` (default 3); presses back
  - `type: "Wait"` — `durationMs` (required)
  - `type: "OpenLink"` — `link` (required)
  - `type: "MaestroYaml"` — `scenarioId` referencing an entry in `fixedScenarios`
- `tags`: list of `- name: "TagName"` entries; used by `run --tags`.
- `maxStep` (default 10): max AI steps per attempt. `maxRetry` (default 3): retries on failure.
- `deviceFormFactor`: `type: "Mobile"` or `type: "Tv"` (default Mobile).
- `imageAssertions`: list of `assertionPrompt` (required) +
  `requiredFulfillmentPercent` (default 80). Checked against the final screenshot by the AI.
- `aiOptions`: per-scenario AI options, e.g. `temperature`, `imageFormat` (`png`/`webp`/`lossy_webp`).

## Variables

- Bare placeholders like `{{search_term}}` in goals are resolved at runtime from
  `run --variables "search_term=wifi"`.
- `{{inputs.name}}` placeholders are only valid inside `reusableScenarios` and are
  resolved from `with:` bindings at load time.

## Reusable scenarios

Define once under `reusableScenarios` (may declare `inputs` with `required`/`default`),
then call from ordinary scenarios with `uses` + `with`, or a `steps` list for multiple
calls. A calling scenario must NOT set `goal`, `maxStep`, `initializationMethods`,
`imageAssertions`, or `userPromptTemplate` — those belong to the reusable definition.
Reusable definitions must NOT have `dependency` or `tags`.

## Complete example

```yaml
scenarios:
- id: "launch-settings"
  goal: "Open the Settings app and confirm the top screen is shown"
  initializationMethods:
  - type: "CleanupData"
    packageName: "com.android.settings"
  - type: "LaunchApp"
    packageName: "com.android.settings"
  tags:
  - name: "Settings"
- id: "open-about-page"
  goal: "Scroll down and open the 'About emulated device' page. Be careful not to open other pages"
  dependency: "launch-settings"
  maxStep: 15
  imageAssertions:
  - assertionPrompt: "The About page is displayed"
    requiredFulfillmentPercent: 80
- id: "search-for-wifi"
  uses: "search-in-settings"
  with:
    term: "{{search_term}}"
reusableScenarios:
- id: "search-in-settings"
  inputs:
    term:
      required: true
  goal: "Search for {{inputs.term}} in the Settings app and verify it appears in search results"
  initializationMethods:
  - type: "LaunchApp"
    packageName: "com.android.settings"
```

Real examples live in `sample-test/src/main/resources/projects/` in the arbigent
repository (e.g. `e2e-test-android.yaml`).

Done when: `arbigent scenarios --project-file=<file>` lists your scenario ids without errors.
""".trimIndent(),
  ),
  ArbigentGuideTopic(
    name = "inspecting-project",
    description = "How to discover what is in a project: list scenarios and tags, settings resolution",
    body = """
# Inspecting an arbigent project

Use these read-only commands before editing or running anything. They need no AI key
and no device.

## List scenarios

    arbigent scenarios --project-file=path/to/project.yaml

Prints one line per runnable scenario: `- <id>: <first 80 chars of goal>...`.
Use the ids with `run --scenario-ids`.

## List tags

    arbigent tags --project-file=path/to/project.yaml

Prints the distinct tag names used across scenarios (or "No tags found"). Use them
with `run --tags`.

## How --project-file is resolved

If you omit `--project-file`, arbigent reads it from the first existing settings file
in this priority order (all under the current working directory):

1. `.arbigent/settings.local.yml`  (highest priority; typically gitignored)
2. `.arbigent/settings.local.yaml`
3. `.arbigent/settings.yml`
4. `.arbigent/settings.yaml`

Keys in these files are CLI option names without `--`. A key nested under a command
name applies only to that command and wins over the global key:

    project-file: "e2e/project.yaml"   # global default for all commands
    log-level: "info"
    run:
      log-level: "debug"               # only `arbigent run` uses debug

So check `.arbigent/` first: the project file may already be configured and
`arbigent scenarios` will work with no options at all.

Done when: you know the scenario ids and tags you need for the next `run` command.
""".trimIndent(),
  ),
  ArbigentGuideTopic(
    name = "running-scenarios",
    description = "How to select and run scenarios and where results are written",
    body = """
# Running scenarios

## Prerequisites

- An AI API key: pass `--ai-type` (`openai` (default) / `gemini` / `azureopenai`) and the
  matching key via the OPENAI_API_KEY / GEMINI_API_KEY / AZURE_OPENAI_API_KEY
  environment variable, `--openai-api-key`-style options, or `.arbigent/settings.local.yml`.
  Never commit keys; use the `settings.local.yml` file (gitignore it).
- A connected device matching `--os` (`android` (default) / `ios` / `web`). The first
  available device is used. Not needed for `--dry-run`.

## Selecting scenarios

- Default (no filter): all leaf scenarios run (scenarios that nothing depends on).
- `--scenario-ids="id1,id2"`: run these scenarios; their `dependency` chains run first.
- `--tags="tag1,tag2"`: run scenarios having any of the tags (OR). Cannot be combined
  with `--scenario-ids`.
- `--shard=1/4`: run the 1st of 4 partitions of the selection (for CI parallelism).
- `--dry-run`: print the selected scenario ids and exit without executing. Use this
  first to confirm your selection.

Example:

    arbigent run --project-file=project.yaml --scenario-ids="open-about-page" --dry-run
    arbigent run --project-file=project.yaml --scenario-ids="open-about-page"

- `--variables 'search_term=wifi,message="hello world"'` fills `{{search_term}}`-style
  placeholders in goals.

## Reading the outcome

Exit code 0 = all selected scenarios succeeded; 1 = at least one failed. Progress and
a "Final Results" summary are logged to stdout.

Everything is written under `arbigent-result/` in the working directory:

- `result.yml` — machine-readable results per scenario (statuses and steps)
- `report.html` — human-readable report
- `screenshots/` — screenshot per step
- `jsonls/` — AI API request/response logs
- `arbigent.log` — full log (path changeable via `--log-file`)

AI decision cache is stored under `arbigent-cache/`.

## One-shot task without a project file

    arbigent run task "Open the Settings app and turn on dark theme" --max-step=15

Runs a single ad-hoc goal on the connected device with the same AI/OS options.

Done when: the exit code is 0, or you have `arbigent-result/` contents to debug
(see `arbigent guide debugging-failures`).
""".trimIndent(),
  ),
  ArbigentGuideTopic(
    name = "debugging-failures",
    description = "How to investigate a failed run: results, logs, screenshots, common causes",
    body = """
# Debugging failed runs

Exit code 1 means at least one selected scenario failed. Investigate in this order:

## 1. Identify what failed

Read the "Final Results" section of stdout, or `arbigent-result/result.yml`, which
records each scenario's status and every step the AI took (actions, feedback, memos).

## 2. Look at the evidence

- `arbigent-result/report.html` — step-by-step report with screenshots inline; the
  fastest way to see where the agent went wrong.
- `arbigent-result/screenshots/` — raw screenshots per step.
- `arbigent-result/jsonls/` — AI API request/response logs. Re-run with
  `--ai-api-logging` for verbose API logging.
- `arbigent-result/arbigent.log` — full log; re-run with `--log-level=debug` for more.

## 3. Reproduce narrowly

    arbigent run --project-file=project.yaml --scenario-ids="<failed-id>" --log-level=debug

Only the failed scenario (plus its dependency chain) runs. Use `--dry-run` first if
you suspect the wrong scenarios were selected.

## Common causes and fixes

- "Missing option '--project-file'" — pass it explicitly or configure `.arbigent/settings.yml`.
- "Missing OpenAI API key" (or Gemini/Azure) — set the environment variable or
  `.arbigent/settings.local.yml`.
- "No available device found" — start an emulator/simulator or connect a device
  matching `--os` before running.
- Goal not achieved within steps — raise `maxStep`, split the scenario using
  `dependency`, or make the `goal` more specific with explicit guardrails.
- Wrong starting state — add `initializationMethods` (`CleanupData`, `LaunchApp`,
  `Back`) so each attempt starts from a known state.
- Image assertion failed — check the final screenshot in the report, then reword
  `assertionPrompt` or adjust `requiredFulfillmentPercent`.
- Flakiness — `maxRetry` (default 3) already retries; prefer fixing the goal or
  initialization over raising it.

Done when: you can state the root cause (selection, environment, starting state, goal
wording, or assertion), not just that the run went red.
""".trimIndent(),
  ),
)
