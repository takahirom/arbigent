# Arbigent CLI & settings reference

This reference maps every arbigent CLI flag to its equivalent **settings-file YAML
key** and **environment variable**, and explains which one wins.

For the *project* YAML (scenarios, goals, assertions) see
[arbigent-yaml-reference.md](arbigent-yaml-reference.md). This file is about the CLI
options and the `.arbigent/settings*.yml` (and `.yaml`) config, which is a different
thing.

## How settings files map to flags

The CLI is built on [Clikt](https://ajalt.github.io/clikt/). Most options are
"settings-aware": they can be supplied from a settings file instead of the command
line. **The YAML key is the option's primary long flag with `--` removed** — it is
*not* nested per provider. (Only the canonical long flag works as a key; short
aliases such as `--openai-key` or `--azure-openai-key` are not valid keys — use
`openai-api-key` / `azure-openai-api-key`.)

```yaml
# .arbigent/settings.local.yml
ai-type: openai
openai-endpoint: https://openrouter.ai/api/v1/
openai-api-key: sk-...              # <-- key is "openai-api-key", NOT "openai.apikey"
openai-model-name: google/gemini-2.5-flash
os: android
log-level: info
```

### Global vs command-scoped keys

A top-level key applies to all commands. Nesting a key under a **command name** scopes
it to that command and takes priority over the global key (the parser flattens nested
maps with `.`, so `run: { log-level: debug }` becomes the key `run.log-level`).
Subcommands nest fully — `run task` uses `run.task.<key>`:

```yaml
log-level: info        # global default for every command
run:
  log-level: debug     # only the `run` command uses debug
  task:
    max-step: 15       # only `run task`
```

### Which settings files are read

The CLI reads **all** of these that exist (relative to the working directory) and
chains them as fallbacks — a value from a higher file wins, but a lower file still
supplies keys the higher one omits:

1. `.arbigent/settings.local.yml`
2. `.arbigent/settings.local.yaml`
3. `.arbigent/settings.yml`
4. `.arbigent/settings.yaml`

## Precedence (what wins)

For a normal settings-aware option, Clikt resolves in this order:

```
command-line flag  >  environment variable  >  settings file  >  built-in default
```

Within the settings files the order is: for each file (in the priority list above),
the command-scoped key (`<command>.<key>`) first, then the global key (`<key>`).

> **iOS real-device options are the exception.** They have no Clikt `envvar`, so their
> environment fallback happens later inside `arbigent-core`, *after* the settings file.
> For those three options the order is: **flag > settings file > env var > default**.

## AI provider / API key mapping

Provider options are only in effect for the matching `--ai-type`. API keys are enforced
at runtime (not by Clikt), so a missing key fails when the run starts, not at parse
time.

| Setting | CLI flag(s) | Settings key | Env var | Default |
|---|---|---|---|---|
| AI type | `--ai-type` | `ai-type` | — | `openai` |
| OpenAI API key | `--openai-api-key`, `--openai-key` | `openai-api-key` | `OPENAI_API_KEY` | (required) |
| OpenAI endpoint | `--openai-endpoint` | `openai-endpoint` | — | `https://api.openai.com/v1/` |
| OpenAI model | `--openai-model-name` | `openai-model-name` | — | `gpt-4.1` |
| Gemini API key | `--gemini-api-key` | `gemini-api-key` | `GEMINI_API_KEY` | (required) |
| Gemini endpoint | `--gemini-endpoint` | `gemini-endpoint` | — | `https://generativelanguage.googleapis.com/v1beta/openai/` |
| Gemini model | `--gemini-model-name` | `gemini-model-name` | — | `gemini-1.5-flash` |
| Azure API key | `--azure-openai-api-key`, `--azure-openai-key` | `azure-openai-api-key` | `AZURE_OPENAI_API_KEY` | (required) |
| Azure endpoint | `--azure-openai-endpoint` | `azure-openai-endpoint` | — | (required) |
| Azure API version | `--azure-openai-api-version` | `azure-openai-api-version` | — | `2024-10-21` |
| Azure model / deployment | `--azure-openai-model-name` | `azure-openai-model-name` | — | `gpt-4.1` |

> API keys and other sensitive values are masked as `****` in `--help` output (any key
> containing `key`, `token`, `secret`, `password`, or `team`).

## iOS real-device mapping

Used with `--os=ios` on a physical device. Env var is *lower* priority than the
settings file here (see the note above).

| Setting | CLI flag | Settings key | Env var | Default |
|---|---|---|---|---|
| Apple team id | `--ios-xctest-apple-team-id` | `ios-xctest-apple-team-id` | `ARBIGENT_IOS_XCTEST_APPLE_TEAM_ID` | auto-detect when signing certs resolve to one team |
| Device UDID | `--ios-real-device-id` | `ios-real-device-id` | `ARBIGENT_IOS_REAL_DEVICE_ID` | auto (when one device) |
| Runner port | `--ios-real-device-port` | `ios-real-device-port` | `ARBIGENT_IOS_REAL_DEVICE_PORT` | `22087` |

## Common options (per command)

All of the following are settings-aware (readable from the settings file) unless noted.

### `run`

| Flag | Settings key | Type | Default |
|---|---|---|---|
| `--project-file` | `project-file` | String | required at runtime |
| `--ai-type` | `ai-type` | `openai` / `gemini` / `azureopenai` | `openai` |
| `--os` | `os` | `android` / `ios` / `web` | `android` |
| `--variables` | `variables` | `k=v,...` map | (none) |
| `--scenario-ids` | `scenario-ids` | comma list | (none → runs all leaf scenarios) |
| `--tags` | `tags` | comma list (OR) | (none) |
| `--shard` | `shard` | `n/m` | `1/1` |
| `--dry-run` | `dry-run` | flag | `false` |
| `--ai-api-logging` | `ai-api-logging` | flag | `false` |
| `--log-level` | `log-level` | `debug`/`info`/`warn`/`error` | `info` |
| `--log-file` | `log-file` | String | `arbigent-result/arbigent.log` |
| `--working-directory` | `working-directory` | String | (none) |
| `--path` | `path` | String | (none) |
| `--ios-*` | see iOS table above | | |

Plus the AI-provider group options for the selected `--ai-type`.

### `run task`

Takes a positional `goal` argument (required), the AI-provider group, `--os`,
`--ios-*`, `--ai-api-logging`, `--log-level`, `--log-file`, `--working-directory`, plus:

| Flag | Settings key | Type | Default |
|---|---|---|---|
| `--max-step` | `max-step` | Int | `10` |
| `--max-retry` | `max-retry` | Int | `3` |

### `scenarios` / `tags`

| Flag | Settings key |
|---|---|
| `--project-file` | `project-file` |
| `--working-directory` | `working-directory` |
| `--log-level` | `log-level` |

### `graph`

`--project-file`, `--log-level` (both settings-aware).

### `instruction`

All settings-aware (keys `scenario-ids`, `include-app-ui-structure`, etc., or
scoped as `instruction.<key>`).

| Flag | Settings key | Notes |
|---|---|---|
| `--project-file` | `project-file` | |
| `--log-level` | `log-level` | |
| `--scenario-ids` | `scenario-ids` | required, comma list |
| `--include-app-ui-structure` | `include-app-ui-structure` | flag, default `false` |

### `guide`

No options. Subcommands are documentation topics that print text: `setup`,
`writing-yaml`, `inspecting-project`, `running-scenarios`, `debugging-failures`,
`reproducing-manually`.

## Full example settings file

```yaml
# .arbigent/settings.local.yml
project-file: e2e/project.yaml
ai-type: openai
openai-endpoint: https://api.openai.com/v1/
openai-api-key: sk-...
openai-model-name: gpt-4.1
os: android
log-level: info

# command-scoped override: only `run` logs at debug
run:
  log-level: debug
```
