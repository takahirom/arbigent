# Replay scripts

Arbigent can write down what it sent to the device while a scenario ran, so the same screens can be reached again later with nothing but `adb`. This is aimed at coding agents (Claude Code, Codex, CI bots) and at people who want to look at the screen a scenario ended on without paying for another AI-driven run.

Replay scripts are **Android only**. The runner speaks `adb`, so a scenario that ran on an iOS simulator or in a browser records nothing; Arbigent logs that it skipped the script and the run is otherwise unaffected. iOS and Web would need their own event mapping and a runner built on `simctl`/`idb` or a browser driver, which does not exist yet.

After a scenario **succeeds**, Arbigent writes three files into the replay-scripts directory:

| File | What it is for |
|---|---|
| `<scenario-id>.jsonl` | The event log: one JSON object per line, with what was sent to the device on each step and what the step was aiming at. This is the source of truth. |
| `<scenario-id>.md` | A readable summary of the same run: goal, numbered steps, the element each step acted on, and the exact `replay.sh` commands. Read this first. |
| `replay.sh` | The runner, shared by every scenario in the directory. A POSIX `sh` wrapper around a python3 script that needs only the standard library and `adb`. |

A failed scenario writes nothing and leaves the previous files untouched, because a half-finished log would replay to a screen the scenario never reached. Files are written through a temporary sibling and renamed into place, so a reader never sees a partial one. A scenario id that is not a plain file name (`open settings/main`) is sanitized and gets a short hash appended (`open_settings_main-3f9a1c`), so two ids that sanitize alike do not overwrite each other.

**The files contain what the run saw and typed.** Typed text, launch extras, opened URLs, the agent's memos and every label on the screens it visited are stored in plain text. Treat the directory like a log: do not enable the feature for a scenario that types a real password, and think about who can download the artifact before uploading it from CI.

## Turning it on

```yaml
settings:
  replayScripts:
    enabled: true
    outputDir: "build/replay-scripts"   # optional
```

`replayScripts` absent from the project file means the feature is off. When `outputDir` is omitted the files go to `replay-scripts/` inside the result directory (`arbigent-result/` for the CLI). A relative `outputDir` resolves against the working directory.

## Replaying

```
./replay.sh open-settings.jsonl                  # replay every step
./replay.sh open-settings.jsonl --with-init      # also clear state and launch the app first
./replay.sh open-settings.jsonl --step 3         # replay one step
./replay.sh open-settings.jsonl --from 2 --until 4
./replay.sh open-settings.jsonl --show           # list the steps, no device needed
```

Options:

- `--with-init` also replays the setup phase (app launch with its recorded extras, state clear). Without it the runner assumes the app is already on the first screen. A setup block that ran in the middle of a scenario (a relaunch before a later task) is replayed only when the step after it is in the selected range; the block before the first step is always replayed, since it is what launches the app.
- `--device SERIAL` picks a device when more than one is attached.
- `--timeout SEC` is how long each step waits for its recorded target or screen hints before giving up (default 10).
- `--no-wait` sends the events without waiting for anything. Useful when the runner's waiting is what is going wrong.
- `--backend auto|android|uiautomator|maestro` picks how the runner reads the view hierarchy. `auto` prefers `uiautomator dump`, which works on release builds too.

Before each step, the runner waits for the element the step acted on (`target`) to appear, matching text, resource id and accessibility id, strictly first and then more loosely. A step that pressed a bare key with no target, such as a "next" on a splash screen, instead waits for any of the `screen` hints recorded for it: up to five identities of elements the AI saw on that screen. At the end it compares the resource ids recorded on the final screen with what is on the device and prints `PASS` or `WARN`.

Exit codes:

| Code | Meaning |
|---|---|
| 0 | Every step was sent. The end-screen check prints `PASS` or `WARN` but does not change the code. |
| 1 | Usage error, an unreadable or unfinished log, an empty step range, `adb` is not on `PATH`, or the chosen backend is not available. |
| 2 | A recorded target never appeared, or an element the recording tapped is not on screen. The app has diverged from the recording. |
| 3 | The device rejected a command (a tap, a launch, `pm clear`), or the hierarchy could not be read at all (no device, a wrong serial, a hung `adb`). Nothing after it was sent. |

Known gaps: `adb shell input text` only types printable ASCII and cannot carry a literal `%s`, so a step that typed either stops with exit code 2. Maestro grants an app its runtime permissions when it launches it, and the runner does not, so a replay that starts from a cleared state may meet a permission dialog the recording never saw. Screen hints are advisory: when none of them shows up in time the runner says so and sends the step anyway, because a hint describes the screen the decision looked at, not what it acted on, and a Compose screen can expose none of them to `uiautomator`.

Exit code 2 is the signal for an agent to stop replaying and drive the app itself from the current screen. The `.md` tells it which step it was on and what that step expected to see.

## The event log

Every line has `type`, `task`, `taskIndex`, `step` and `ts`. The line types are:

- `scenario_start`: the goal, the app id, and the screen size in the coordinate space the bounds use.
- `decision`: what the AI decided on a step (`action`, `log`, `memo`, `screenshot`) and the `screen` hints.
- `target`: the element the step acted on, with `occurrence`, `bounds` (`[left,top][right,bottom]`) and `center`.
- `init`: an event sent during the task's setup phase (`launch_app` with `launchArguments`, `clear_state`).
- `device`: an event sent during a step (`tap`, `tap_element`, `key_press`, `input_text`, `swipe`, `wait`, `open_link`, `stop_app`). A `tap_element` keeps the text or id pattern the agent clicked by, and the runner finds that element in the current hierarchy before tapping, so a layout that moved still gets the right tap. Anything the runner cannot reproduce is recorded as `unsupported` with the command name, so the gap is visible instead of silent.
- `scenario_end`: `status` and the resource-id `signature` of the final screen.

Coordinates are device pixels, the same space `adb shell input tap` uses.

## Using the scripts from CI

The scripts are only generated; nothing in CI replays them. A typical setup runs Arbigent on a schedule, uploads the directory, and lets whoever needs a screen download it:

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
            adb install app/build/outputs/apk/release/app-release.apk
            ./arbigent run --project-file=arbigent-project.yaml --os=android
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: replay-scripts
          path: arbigent-result/replay-scripts
```

A coding agent that needs to see, say, the settings screen then downloads the artifact, reads `open-settings.md`, and runs `./replay.sh open-settings.jsonl --with-init` against its own emulator. If the runner exits 2, the agent continues by hand from the step named in the output.
