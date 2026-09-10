# Arbigent project YAML reference

This is a complete reference for the fields in an arbigent **project file** (the YAML
passed to commands via `--project-file`, or resolved from a `.arbigent/settings.yml`
/ `.arbigent/settings.local.yml` file — `.yaml` variants are also accepted).

> [!WARNING]
> The YAML format is still under development and may change in the future.

For a task-oriented introduction (how to write scenarios, variables, reusable
scenarios), see `arbigent guide writing-yaml`. Real examples live in
`sample-test/src/main/resources/projects/` (e.g. `e2e-test-android.yaml`,
`nowinandroidsample.yaml`).

## How to read this reference

- The serializer is **not strict**: unknown keys are silently ignored, so a typo in an
  optional field name does not fail the load — it just does nothing. Running
  `arbigent scenarios --project-file=<file>` confirms the file loads and lists the
  resulting scenario ids, but it cannot detect an ignored optional-key typo.
- Defaults are **not written** when a file is saved (`encodeDefaults = false`), so a
  real file usually shows only a subset of these keys. An optional field left out takes
  its default; a required field left out fails decoding.
- Sealed types (`type`, `initializationMethods`, `deviceFormFactor`,
  `cacheStrategy.aiDecisionCacheStrategy`, `cleanupData`, launch argument values) are
  tagged with a `type:` field that selects the variant.

## Top level: project file

| Key | Type | Default | Description |
|---|---|---|---|
| `scenarios` | list of [Scenario](#scenario) | **required** | Scenarios to run. |
| `reusableScenarios` | list of [Scenario](#scenario) | `[]` | Parameterized scenarios that other scenarios call via `uses`/`steps`. |
| `fixedScenarios` | list of [FixedScenario](#fixedscenario) | `[]` | Named Maestro YAML snippets used by the `MaestroYaml` initialization method. |
| `settings` | [Settings](#settings) | `{}` | Project-wide settings (prompts, cache, AI options). |

## Scenario

Used both in `scenarios` and `reusableScenarios`.

| Key | Type | Default | Description |
|---|---|---|---|
| `id` | String | random UUID | Stable identifier used by `--scenario-ids` and `dependency`. Avoid `/`, `#`, `@`. |
| `goal` | String (multiline) | `""` | Natural-language goal the AI agent tries to achieve. |
| `type` | [ScenarioType](#scenariotype) | `Scenario` | Scenario kind (a `type:`-tagged object, not a scalar). |
| `dependency` | String? | `null` | `id` of a scenario that must run first. |
| `uses` | String? | `null` | `id` of a reusable scenario to invoke (single call). |
| `with` | Map<String,String> | `{}` | Bindings for the reusable scenario's `inputs`. |
| `steps` | list of [ReusableStep](#reusablestep) | `[]` | Multiple reusable-scenario calls in order. |
| `inputs` | Map<String,[ReusableInput](#reusableinput)> | `{}` | Declared inputs (only meaningful on a `reusableScenarios` entry). |
| `initializationMethods` | list of [InitializationMethod](#initializationmethod) | `[]` | Setup actions run before the goal. |
| `initializeMethods` | [InitializationMethod](#initializationmethod) | `Noop` | **Deprecated**; use `initializationMethods`. |
| `noteForHumans` | String (multiline) | `""` | Free-form note; not sent to the AI. |
| `maxRetry` | Int? | `null` | Retries on failure. Unset falls back to `settings.maxRetry`, then to `3`. |
| `maxStep` | Int | `10` | Max AI steps per attempt. |
| `tags` | Set of [Tag](#tag) | `[]` | Tags for `run --tags` (serialized as a sequence). |
| `deviceFormFactor` | [DeviceFormFactor](#deviceformfactor) | `Unspecified` | Target form factor. |
| `cleanupData` | [CleanupData](#cleanupdata) | `Noop` | **Legacy / no longer used** (kept for backward compatibility). |
| `imageAssertionHistoryCount` | Int | `1` | Number of recent screenshots the image assertion looks at. |
| `imageAssertions` | list of [ImageAssertion](#imageassertion) | `[]` | AI checks against the final screenshot(s). |
| `userPromptTemplate` | String | built-in default | Overrides the per-step user prompt template. |
| `aiOptions` | [AiOptions](#aioptions)? | `null` | Per-scenario AI options. |
| `cacheOptions` | [CacheOptions](#cacheoptions)? | `null` | Per-scenario cache overrides. |
| `additionalActions` | `List<String>?` | `null` | Extra actions available to the agent. |
| `mcpOptions` | [McpOptions](#mcpoptions)? | `null` | Per-scenario MCP server enable/disable. |

> A calling scenario (one that sets `uses`/`steps`) is validated at load time: `uses`
> and `steps` are mutually exclusive, and it must NOT set `goal`, `maxStep`,
> `initializationMethods`, `imageAssertions`, `userPromptTemplate`, `type: Execution`,
> a non-default `imageAssertionHistoryCount`, the legacy `initializeMethods`, or the
> per-task execution options `aiOptions` / `cacheOptions` / `additionalActions` /
> `mcpOptions` — those belong to the reusable definition. Scenario-run level keys such as
> `maxRetry` is allowed, since the executor reads it for the run as a whole. Reusable definitions must NOT have `dependency` or `tags`.

### ScenarioType

The `type` field is itself a `type:`-tagged object (property polymorphism), not a
scalar. Variants: `Scenario` (default) and `Execution`, neither with extra fields:

```yaml
type:
  type: "Execution"
```

### CleanupData

Legacy scenario-level field, tagged by `type:`. Distinct from the `CleanupData`
[initialization method](#initializationmethod).

| `type` | Fields |
|---|---|
| `Noop` | (none) |
| `Cleanup` | `packageName: String` |

## Settings

| Key | Type | Default | Description |
|---|---|---|---|
| `prompt` | [Prompt](#prompt) | `{}` | System/user prompt configuration. |
| `cacheStrategy` | [CacheStrategy](#cachestrategy) | `{}` | AI-decision caching. |
| `aiOptions` | [AiOptions](#aioptions)? | `null` | Project-wide AI options. |
| `mcpJson` | String (multiline) | `"{}"` | MCP server configuration as a JSON string. |
| `deviceFormFactor` | [DeviceFormFactor](#deviceformfactor) | `Unspecified` | Default form factor for all scenarios. |
| `additionalActions` | `List<String>?` | `null` | Project-wide extra actions. |
| `maxRetry` | Int? | `null` | Default retry count for scenarios that do not set their own. Unset means `3`. |
| `positionComments` | Boolean | `true` | Whether saving (UI) and `arbigent sort` write a `# tree: root > ... > id \| children: ...` comment above each scenario. The comment is derived from `dependency` and regenerated on every write; `false` removes it. See [Scenario order and position comments](#scenario-order-and-position-comments). |

### Prompt

| Key | Type | Default | Description |
|---|---|---|---|
| `systemPrompts` | list of String | built-in | System prompts for mobile/web. |
| `systemPromptsForTv` | list of String | built-in | System prompts for TV form factor. |
| `additionalSystemPrompts` | list of String | `[]` | Extra system prompts appended to the defaults. |
| `userPromptTemplate` | String | built-in default | Per-step user prompt template. |
| `appUiStructure` | String (multiline) | `""` | Description of the app's screens/navigation given to the AI. |
| `scenarioGenerationCustomInstruction` | String (multiline) | `""` | Custom instruction used when generating scenarios. |

### CacheStrategy

| Key | Type | Default | Description |
|---|---|---|---|
| `aiDecisionCacheStrategy` | [AiDecisionCacheStrategy](#aidecisioncachestrategy) | `Disabled` | Backend for the AI-decision cache. |
| `replayWithFallback` | Boolean | `false` | Replay the actions a scenario recorded on its last successful run instead of asking the AI for each step, reproducing the recorded pacing so the app is driven as it was when it passed. Project-wide only: replay is decided once for a whole run. A scenario with no `imageAssertions` is run normally, since they are what verifies a replayed run. If a recorded target element is gone, or an assertion fails, only that task is re-run in normal AI-driven mode and the tasks after it go back to replaying; the tasks before it keep what they replayed. If that task fails again, the whole scenario restarts in normal AI-driven mode. |

#### AiDecisionCacheStrategy

Tagged by `type:`.

| `type` | Fields |
|---|---|
| `Disabled` | (none) |
| `InMemory` | `maxCacheSize: Long = 100`, `expireAfterWriteMs: Long` (default 24h in ms) |
| `Disk` | `maxCacheSize: Long` (default `524288000`, i.e. 500 MiB) |

## AiOptions

Available on a scenario and on `settings`.

| Key | Type | Default | Description |
|---|---|---|---|
| `temperature` | Double? | `null` | Sampling temperature. |
| `imageDetail` | `high` \| `low` | `null` | Image detail hint sent to the model. |
| `imageFormat` | `png` \| `webp` \| `lossy_webp` | `null` | Screenshot encoding sent to the model. |
| `historicalStepLimit` | Int? | `null` | How many prior steps to include in the prompt. |
| `extraBody` | `JsonObject?` (YAML map) | `null` | Extra fields merged into the request body. |
| `useResponsesApi` | Boolean? | `null` | Use the OpenAI Responses API. |

## InitializationMethod

Each entry is tagged by `type:`.

| `type` | Fields | Description |
|---|---|---|
| `LaunchApp` | `packageName: String` (required), `launchArguments: Map<String,`[ArgumentValue](#argumentvalue)`> = {}` | Launch an app. |
| `CleanupData` | `packageName: String` (required) | Clear app data. |
| `Back` | `times: Int = 3` | Press back N times. |
| `Wait` | `durationMs: Long` (required) | Wait a fixed duration. |
| `OpenLink` | `link: String` (required) | Open a URL/deeplink. |
| `MaestroYaml` | `scenarioId: String` (required), `yamlContent: String? = null` | Run a `fixedScenarios` entry (or inline Maestro YAML). |
| `Noop` | (none) | Do nothing. |

### ArgumentValue

Values in `launchArguments`, tagged by `type:`.

| `type` | Fields |
|---|---|
| `String` | `value: String` |
| `Int` | `value: Int` |
| `Boolean` | `value: Boolean` |

## ImageAssertion

| Key | Type | Default | Description |
|---|---|---|---|
| `assertionPrompt` | String | **required** | Statement the AI verifies against the screenshot. |
| `requiredFulfillmentPercent` | Int | `80` | Minimum confidence for the assertion to pass. |

## CacheOptions

| Key | Type | Default | Description |
|---|---|---|---|
| `forceCacheDisabled` | Boolean | `false` | Disable AI-decision caching for this scenario. |

## McpOptions

| Key | Type | Default | Description |
|---|---|---|---|
| `mcpServerOptions` | `List<`[McpServerOption](#mcpserveroption)`>?` | `null` | Per-server enable/disable. |

### McpServerOption

| Key | Type | Description |
|---|---|---|
| `name` | String | Server name (must match a key in `settings.mcpJson`). |
| `enabled` | Boolean | Whether the server is enabled for this scenario. |

## FixedScenario

| Key | Type | Default | Description |
|---|---|---|---|
| `id` | String | random UUID | Identifier. |
| `type` | String | `"maestro yaml"` | Snippet type. |
| `title` | String | **required** | Human-readable title. |
| `description` | String | **required** | Human-readable description. |
| `yamlText` | String (multiline) | **required** | The Maestro YAML body. |

## ReusableStep

| Key | Type | Default | Description |
|---|---|---|---|
| `uses` | String | **required** | `id` of the reusable scenario to invoke. |
| `with` | Map<String,String> | `{}` | Input bindings for that scenario. |

## ReusableInput

| Key | Type | Default | Description |
|---|---|---|---|
| `required` | Boolean | `false` | Whether the input must be supplied. |
| `default` | String? | `null` | Value used when not supplied. |

## Tag

| Key | Type | Description |
|---|---|---|
| `name` | String | Tag name; matched by `run --tags`. |

## DeviceFormFactor

Tagged by `type:`: `Mobile`, `Tv`, or `Unspecified` (default). No other fields.

## Scenario order and position comments

The order of `scenarios` is the order `arbigent run` runs them in and the order `--shard`
splits them by. The UI saves scenarios in dependency order (depth-first): a scenario comes after the
scenario it depends on, and its whole subtree comes before the next sibling. Roots and
siblings keep their declared order. `arbigent sort`
brings a hand-edited file back to that order and `arbigent sort --diff` fails in CI when
a file has drifted.

Both also write a *position comment* above each scenario. It is not a field: it is a
YAML comment derived from `dependency`, rewritten on every save or sort, and safe to
delete (set `settings.positionComments: false` to stop writing it). It exists so that a
reader of one scenario can see its ancestors and its direct dependents without
searching the file. One header line directly under `scenarios:` says that these lines are
generated and how to refresh them:

```yaml
scenarios:
# The "# tree:" lines below are generated from `dependency` by `arbigent sort` (and on UI save); do not edit them, rerun `arbigent sort`.
# tree: launch-app | children: open-search
- id: "launch-app"
  goal: "Launch the app"
# tree: launch-app > open-search | children: type-keyword
- id: "open-search"
  goal: "Open the search screen"
  dependency: "launch-app"
# tree: launch-app > open-search > type-keyword
- id: "type-keyword"
  goal: "Type a keyword into the search box"
  dependency: "open-search"
```

`arbigent sort` moves whole `- id:` blocks and leaves every other line as it is, so quoting,
blank lines and your own comments survive. The one reserved form is a comment starting with
`# tree:` right above a scenario: the sorter treats it as its own and rewrites or removes it.
Before writing it re-reads the result and refuses
to change the file if the project would decode differently, which needs every scenario to have
an explicit `id`.

## Minimal example

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
settings:
  prompt:
    appUiStructure: |-
      The Settings app opens on a scrollable list of top-level entries...
```
