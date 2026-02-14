#!/usr/bin/env bash
set -euo pipefail

# AndroidWorld emulator setup script
# Creates a Pixel 6, API 33 (Google APIs) AVD and installs required apps.
#
# Usage:
#   # Step 1: Create AVD (one-time)
#   ./benchmark/android_world/setup_emulator.sh create
#
#   # Step 2: Launch emulator
#   ./benchmark/android_world/setup_emulator.sh launch
#
#   # Step 3: Install AndroidWorld apps (one-time, after emulator is running)
#   ./benchmark/android_world/setup_emulator.sh setup-apps

ANDROID_SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
AVD_NAME="AndroidWorldAvd"
SYSTEM_IMAGE="system-images;android-33;google_apis;arm64-v8a"
DEVICE="pixel_6"

case "${1:-help}" in

create)
  echo "=== Installing cmdline-tools (if needed) ==="
  if [ ! -f "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "cmdline-tools not found. Please install via Android Studio:"
    echo "  Settings > Languages & Frameworks > Android SDK > SDK Tools > Android SDK Command-line Tools"
    echo ""
    echo "Or download from: https://developer.android.com/studio#command-line-tools-only"
    echo "and extract to $ANDROID_SDK/cmdline-tools/latest/"
    exit 1
  fi

  SDKMANAGER="$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager"
  AVDMANAGER="$ANDROID_SDK/cmdline-tools/latest/bin/avdmanager"

  echo "=== Installing Google APIs system image (API 33) ==="
  yes | "$SDKMANAGER" --install "$SYSTEM_IMAGE" || true

  echo "=== Deleting old AVD (if exists) ==="
  "$AVDMANAGER" delete avd --name "$AVD_NAME" 2>/dev/null || true

  echo "=== Creating AVD: $AVD_NAME ==="
  echo "no" | "$AVDMANAGER" create avd \
    --name "$AVD_NAME" \
    --package "$SYSTEM_IMAGE" \
    --device "$DEVICE" \
    --force

  echo ""
  echo "Done! AVD '$AVD_NAME' created."
  echo "Next: ./benchmark/android_world/setup_emulator.sh launch"
  ;;

launch)
  echo "=== Launching emulator ==="
  echo "Press Ctrl+C to stop."
  "$ANDROID_SDK/emulator/emulator" \
    -avd "$AVD_NAME" \
    -no-snapshot \
    -grpc 8554
  ;;

setup-apps)
  echo "=== Installing AndroidWorld apps ==="
  echo "This may take several minutes..."

  SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
  REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
  VENV="$REPO_ROOT/.venv-android-world"

  if [ ! -f "$VENV/bin/python" ]; then
    echo "Error: Python venv not found at $VENV"
    echo "Create it first: uv venv --python 3.11 .venv-android-world"
    exit 1
  fi

  REPO_ROOT_ESCAPED="$REPO_ROOT" "$VENV/bin/python" -c "
import os, sys
sys.path.insert(0, os.path.join(os.environ['REPO_ROOT_ESCAPED'], 'tmp/android_world'))
from android_world.env import env_launcher

potential_paths = [
    os.path.expanduser('~/Library/Android/sdk/platform-tools/adb'),
    os.path.expanduser('~/Android/Sdk/platform-tools/adb'),
    os.path.join(os.environ.get('ANDROID_HOME', ''), 'platform-tools', 'adb'),
    os.path.join(os.environ.get('ANDROID_SDK_ROOT', ''), 'platform-tools', 'adb'),
]
adb_path = next((p for p in potential_paths if os.path.isfile(p)), None)
if not adb_path:
    raise EnvironmentError('adb not found. Set ANDROID_HOME or install Android SDK.')
env = env_launcher.load_and_setup_env(
    console_port=5554,
    emulator_setup=True,
    adb_path=adb_path,
)
print('Setup complete! All apps installed.')
env.close()
" || exit $?
  ;;

help|*)
  echo "Usage: $0 {create|launch|setup-apps}"
  echo ""
  echo "  create      Install system image, delete old AVD, create new AVD"
  echo "  launch      Launch the emulator with -grpc 8554"
  echo "  setup-apps  Install all AndroidWorld apps (emulator must be running)"
  ;;

esac
