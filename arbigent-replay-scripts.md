# Replay scripts

Arbigent can write down what it sent to the device while a scenario ran, so the same screens can be reached again later without an AI-driven run. This is aimed at coding agents (Claude Code, Codex, CI bots) and at people who want to look at the screen a scenario ended on without paying for another run.

A log is replayed by the `arbigent replay` subcommand, so nothing but arbigent itself is needed. In a repository that has no arbigent installed, the [`arbigentw` wrapper](#getting-arbigent-to-replay-with) downloads and verifies a pinned release on first use, the way `gradlew` does.

After a scenario **succeeds** and recorded at least one device event, Arbigent writes two files into the replay-scripts directory (a successful run that sent nothing to the device writes nothing, since there is nothing to replay):

| File | What it is for |
|---|---|
| `<scenario-id>.jsonl` | The event log: one JSON object per line, with what was sent to the device on each step and what the step was aiming at. This is the source of truth. |
| `<scenario-id>.md` | A readable summary of the same run: goal, numbered steps, the element each step acted on, and the exact commands that replay them. Read this first. |

A failed scenario writes nothing and leaves the previous files untouched, because a half-finished log would replay to a screen the scenario never reached. Files are written through a temporary sibling and renamed into place, so a reader never sees a partial one. A scenario id that is not a plain file name (`open settings/main`) is sanitized and gets a short hash appended (`open_settings_main-3f9a1c`), so two ids that sanitize alike do not overwrite each other.

A log records which kind of device it was recorded on and replays only on that kind. Replaying is driven through the same device layer a scenario run uses, so an Android log needs an Android device and an iOS log an iOS one.

## Turning it on

```yaml
settings:
  replayScripts:
    enabled: true
    outputDir: "build/replay-scripts"   # optional
```

`replayScripts` absent from the project file means the feature is off. When `outputDir` is omitted the files go to `replay-scripts/` inside the result directory (`arbigent-result/` for the CLI). A relative `outputDir` resolves against the working directory.

## Replaying

```sh
arbigent replay open-settings.jsonl                  # replay every step
arbigent replay open-settings.jsonl --with-init      # also clear state and launch the app first
arbigent replay open-settings.jsonl --step 3         # replay one step
arbigent replay open-settings.jsonl --from 2 --until 4
arbigent replay open-settings.jsonl --show           # list the steps, no device needed
```

No API key is needed and no model is called: the log already says what to send.

Options:

- `--with-init` also replays the setup phase (app launch with its recorded extras, state clear). Without it the replay assumes the app is already on the first screen. A setup block that ran in the middle of a scenario (a relaunch before a later task) is replayed only when the step after it is in the selected range; the block before the first step is always replayed, since it is what launches the app.
- `--step N` replays a single step; `--from N` and `--until N` bound a range.
- `--show` prints what would be sent and exits without connecting to a device.
- `--no-wait` skips the readiness waits: the wait for the step's target, the wait for the screen hints and the wait for the end screen. A `wait` the recording itself sent is still sent, because it is part of the interaction rather than a guess about readiness. Useful when the waiting is what is going wrong.
- `--ios-xctest-apple-team-id`, `--ios-real-device-id` and `--ios-real-device-port` are the same physical-iPhone options `arbigent run` takes.

Before each step, the replay waits for the element the step acted on (`target`) to appear, matching text, resource id and accessibility id, and for the screen to stop changing. A step that pressed a bare key with no target, such as a "next" on a splash screen, instead waits for any of the `screen` hints recorded for it: up to five identities of elements the AI saw on that screen. How long it waits comes from the gap the recording itself had between the steps, never below 10 seconds nor above a minute, because a short recorded gap says the recording run was fast, not that the replayed screen will be.

Taps and swipes recorded as coordinates are scaled from the screen size in the log to the size of the connected device. They are not sent as percentages, because Maestro rounds a percentage to a whole percent, which is about 11 px on a 1080 px-wide screen. When either size is missing from the log or cannot be read from the device, the recorded coordinates are sent unchanged: a scale guessed from one known size would move a tap further from its element than leaving it where it was recorded.

At the end, when the selection reached the last recorded step, the resource ids recorded on the final screen are compared with what is on the device.

Exit codes:

| Code | Meaning |
|---|---|
| 0 | Every selected step was sent. The end screen is checked against the recorded ids only when the selection reached the recorded final step and the log carries a signature; a partial selection has no recorded end screen to be at, and a log recorded without one has nothing to compare. |
| 1 | The log or the range cannot be replayed at all: an unreadable log, one that is not a single finished successful run (no successful `scenario_end` as its last line, a second `scenario_start`, or lines from another scenario), a schema version this arbigent does not read, a platform it does not know, a step number below 1, `--from` after `--until`, an empty step range, or a recorded command the replay cannot reproduce. Nothing was sent. |
| 2 | A recorded target never appeared, an element the recording tapped is not on screen, or none of the recorded end-screen ids is present. The app has diverged from the recording. |
| 3 | The device rejected a command, or the hierarchy could not be read at all. Nothing after it was sent. |

Exit code 2 is the signal for an agent to stop replaying and drive the app itself from the current screen. The output names the step it stopped on and prints the `--from` command that resumes from there, and the `.md` says what that step expected to see.

Known gaps: a command that cannot be reproduced faithfully is recorded as `unsupported`, and a selection containing one is refused before anything is sent (exit code 1) rather than being replayed as a different interaction. Screen hints are advisory: when none of them shows up in time the replay says so and sends the step anyway, because a hint describes the screen the decision looked at, not what it acted on, and a Compose screen can expose none of them to the hierarchy dump. Typed text is recorded as the command carried it, before Maestro expanded any `${...}` in it, so text holding such an expression is replayed as the expression rather than as the value the recorded run typed. Nothing selects between several attached Android devices yet; the replay uses the same device the device layer would pick for a run.

## Getting arbigent to replay with

An arbigent that is already installed replays a log directly with `arbigent replay`. A repository that would rather pin a version commits the wrapper instead:

```sh
arbigent wrapper --version 0.80.0
./arbigentw replay open-settings.jsonl --with-init
```

`./arbigentw` downloads the pinned release on first use, verifies its SHA-256 against the digest recorded in `.arbigent/wrapper/arbigent-wrapper.properties`, unpacks it under `~/.arbigent/wrapper/` and runs it. Later runs use the unpacked copy and never touch the network. The checksum is mandatory: the wrapper refuses to install a distribution it cannot verify. Generating the wrapper needs an arbigent once, so one person in a team runs `arbigent wrapper` and commits the two files; everyone else, and CI, only needs `./arbigentw`. The commands the `.md` summary prints use `./arbigentw`, which is what a downloaded artifact can rely on.

## The event log

Every line has `type`, `task`, `taskIndex`, `step` and `ts`. The line types are:

- `scenario_start`: `schemaVersion`, the `platform` the run was recorded on, the goal, the app id (only when the setup launched an app), and the screen size in the coordinate space the bounds use (`width`/`height`, only when the device reported them).
- `decision`: what the AI decided on a step (`action`, `log`, `memo`, `screenshot`) and the `screen` hints.
- `target`: the element the step acted on, with `occurrence`; `bounds` (`[left,top][right,bottom]`) and `center` are present only when the element's geometry was known.
- `init`: an event sent during the task's setup phase (`launch_app` with `launchArguments`, `clear_state`).
- `device`: an event sent during a step (`tap`, `tap_element`, `key_press`, `input_text`, `swipe`, `wait`, `open_link`, `stop_app`). A `tap_element` keeps the text or id pattern the agent clicked by, and the replay finds that element in the current hierarchy before tapping, so a layout that moved still gets the right tap. Anything the replay cannot reproduce faithfully is recorded as `unsupported` with the command name, so the gap is visible instead of silent: a long press, a repeated tap, a tap at a relative point, a selector narrowed by position, traits or state, a swipe anchored on an element, and `killApp` all fall back to the agent rather than being replayed as a different interaction.
- `scenario_end`: `status` and the resource-id `signature` of the final screen.

Coordinates are in the coordinate space the recorded `width`/`height` describe, which is the one the device layer works in: Maestro's grid, whose units happen to be pixels on Android but not necessarily on iOS.

## Using the scripts from CI

The scripts are only generated; nothing in CI replays them. A typical setup runs Arbigent on a schedule, uploads the directory, and lets whoever needs a screen download it.

A log records what the run actually typed and how it launched the app, so `input_text` values and `launchArguments` appear in it verbatim. Treat the artifact as being as readable as the accounts and arguments the recorded run used: record with a throwaway account rather than a real one, keep passwords, tokens and personal data out of the scenarios that are recorded, and restrict who can download the artifact when a recording cannot avoid them.

```yaml
name: record-replay-scripts
on:
  schedule:
    - cron: "0 3 * * 1-5"
  workflow_dispatch:

jobs:
  record:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Start an emulator
        uses: reactivecircus/android-emulator-runner@v2
        with:
          api-level: 34
          arch: x86_64
          script: |
            # Build (or download from an earlier job) the APK the scenarios exercise first.
            ./gradlew :app:assembleRelease
            adb install app/build/outputs/apk/release/app-release.apk
            ./arbigentw run --project-file=arbigent-project.yaml --os=android
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: replay-scripts
          path: build/replay-scripts   # the outputDir from the project file above; without outputDir it is arbigent-result/replay-scripts
```

A coding agent that needs to see, say, the settings screen then downloads the artifact, reads `open-settings.md`, and runs `./arbigentw replay open-settings.jsonl --with-init` against its own emulator. If it exits 2, the agent continues by hand from the step named in the output.
