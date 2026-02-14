# AndroidWorld Benchmark

Run [AndroidWorld](https://github.com/google-research/android_world) (116 tasks) with Arbigent.

## Prerequisites

- Android SDK with emulator
- Arbigent CLI built: `./gradlew installDist`
- Python 3.11+ (via `uv`)

## Setup

```bash
# 1. Create Python venv and install dependencies
uv venv --python 3.11 .venv-android-world
uv pip install --python .venv-android-world/bin/python \
  -r tmp/android_world/requirements.txt

# 2. Create AVD (Pixel 6, API 33, Google APIs)
./benchmark/android_world/setup_emulator.sh create

# 3. Launch emulator (in a separate terminal)
./benchmark/android_world/setup_emulator.sh launch

# 4. Install required apps (first time only)
./benchmark/android_world/setup_emulator.sh setup-apps
```

## Run

```bash
source .venv-android-world/bin/activate

# Run specific tasks
python benchmark/android_world/run.py \
  --tasks=CameraTakePhoto,ClockStopWatchRunning

# Run all 116 tasks
python benchmark/android_world/run.py

# Options
python benchmark/android_world/run.py \
  --arbigent_max_step=15 \
  --arbigent_max_retry=5 \
  --tasks=MarkorCreateNoteAndSms
```
