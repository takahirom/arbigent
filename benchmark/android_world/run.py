"""Run AndroidWorld benchmark with Arbigent agent.

Usage:
    python benchmark/android_world/run.py \
        --arbigent_bin=./arbigent-cli/build/install/arbigent/bin/arbigent \
        --tasks=CameraTakePhoto,ClockStopWatchRunning

    # Run all tasks:
    python benchmark/android_world/run.py \
        --arbigent_bin=./arbigent-cli/build/install/arbigent/bin/arbigent
"""

import os
import sys
from collections.abc import Sequence

from absl import app
from absl import flags
from absl import logging

# Add android_world to path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "../../tmp/android_world"))

from android_world import checkpointer as checkpointer_lib
from android_world import registry
from android_world import suite_utils
from android_world.env import env_launcher

from arbigent_agent import ArbigentAgent

logging.set_verbosity(logging.WARNING)

os.environ["GRPC_VERBOSITY"] = "ERROR"
os.environ["GRPC_TRACE"] = "none"

_ADB_PATH = flags.DEFINE_string(
    "adb_path",
    None,
    "Path to adb. Auto-detected if not set.",
)
_CONSOLE_PORT = flags.DEFINE_integer(
    "console_port",
    5554,
    "The console port of the running Android emulator.",
)
_EMULATOR_SETUP = flags.DEFINE_boolean(
    "perform_emulator_setup",
    False,
    "Whether to perform emulator setup (one-time only).",
)
_TASKS = flags.DEFINE_list(
    "tasks",
    None,
    "List of specific tasks to run. If None, run all tasks.",
)
_TASK_RANDOM_SEED = flags.DEFINE_integer(
    "task_random_seed",
    30,
    "Random seed for task parameter generation.",
)
_N_TASK_COMBINATIONS = flags.DEFINE_integer(
    "n_task_combinations",
    1,
    "Number of parameter combinations per task.",
)
_CHECKPOINT_DIR = flags.DEFINE_string(
    "checkpoint_dir",
    "",
    "Directory to save/resume checkpoints.",
)
_OUTPUT_PATH = flags.DEFINE_string(
    "output_path",
    os.path.expanduser("~/android_world/arbigent_runs"),
    "Directory to save results.",
)

# Arbigent-specific flags
_ARBIGENT_BIN = flags.DEFINE_string(
    "arbigent_bin",
    "./arbigent-cli/build/install/arbigent/bin/arbigent",
    "Path to Arbigent CLI binary.",
)
_ARBIGENT_MAX_STEP = flags.DEFINE_integer(
    "arbigent_max_step",
    10,
    "Max steps per task for Arbigent.",
)
_ARBIGENT_MAX_RETRY = flags.DEFINE_integer(
    "arbigent_max_retry",
    3,
    "Max retries per task for Arbigent.",
)


def _find_adb_directory() -> str:
    potential_paths = [
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
        os.path.expanduser("~/Android/Sdk/platform-tools/adb"),
        os.path.join(os.environ.get("ANDROID_HOME", ""), "platform-tools", "adb"),
        os.path.join(os.environ.get("ANDROID_SDK_ROOT", ""), "platform-tools", "adb"),
    ]
    for path in potential_paths:
        if os.path.isfile(path):
            return path
    raise EnvironmentError("adb not found. Set --adb_path explicitly.")


def _main() -> None:
    adb_path = _ADB_PATH.value or _find_adb_directory()

    env = env_launcher.load_and_setup_env(
        console_port=_CONSOLE_PORT.value,
        emulator_setup=_EMULATOR_SETUP.value,
        adb_path=adb_path,
    )

    try:
        task_registry = registry.TaskRegistry()
        suite = suite_utils.create_suite(
            task_registry.get_registry(
                family=registry.TaskRegistry.ANDROID_WORLD_FAMILY
            ),
            n_task_combinations=_N_TASK_COMBINATIONS.value,
            seed=_TASK_RANDOM_SEED.value,
            tasks=_TASKS.value,
        )
        suite.suite_family = registry.TaskRegistry.ANDROID_WORLD_FAMILY

        agent = ArbigentAgent(
            env,
            arbigent_bin=_ARBIGENT_BIN.value,
            max_step=_ARBIGENT_MAX_STEP.value,
            max_retry=_ARBIGENT_MAX_RETRY.value,
        )
        agent.transition_pause = None
        agent.name = "arbigent"

        if _CHECKPOINT_DIR.value:
            checkpoint_dir = _CHECKPOINT_DIR.value
        else:
            checkpoint_dir = checkpointer_lib.create_run_directory(
                _OUTPUT_PATH.value
            )

        print("Starting AndroidWorld benchmark with Arbigent agent.")
        print(f"Results will be saved to: {checkpoint_dir}")

        suite_utils.run(
            suite,
            agent,
            checkpointer=checkpointer_lib.IncrementalCheckpointer(checkpoint_dir),
        )

        print(f"Finished. Results saved to: {checkpoint_dir}")
    finally:
        env.close()


def main(argv: Sequence[str]) -> None:
    del argv
    _main()


if __name__ == "__main__":
    app.run(main)
