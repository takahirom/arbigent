"""Arbigent agent for AndroidWorld benchmark."""

import logging
import subprocess

from android_world.agents import base_agent
from android_world.env import interface


class ArbigentAgent(base_agent.EnvironmentInteractingAgent):
    """An agent that delegates UI interaction to Arbigent CLI.

    Arbigent connects to the same Android device via Maestro/adb and
    autonomously navigates the UI to achieve the given goal.
    """

    def __init__(
        self,
        env: interface.AsyncEnv,
        arbigent_bin: str,
        max_step: int = 10,
        max_retry: int = 3,
        openai_endpoint: str | None = None,
        openai_model: str | None = None,
        name: str = "ArbigentAgent",
    ):
        super().__init__(env, name, transition_pause=None)
        self._arbigent_bin = arbigent_bin
        self._arbigent_max_step = max_step
        self._arbigent_max_retry = max_retry
        self._openai_endpoint = openai_endpoint
        self._openai_model = openai_model

    def step(self, goal: str) -> base_agent.AgentInteractionResult:
        # Wait for environment to settle before running CLI
        self.get_post_transition_state()

        cmd = [
            self._arbigent_bin,
            "run",
            "task",
            goal,
            "--max-step",
            str(self._arbigent_max_step),
            "--max-retry",
            str(self._arbigent_max_retry),
        ]
        if self._openai_endpoint:
            cmd.extend(["--openai-endpoint", self._openai_endpoint])
        if self._openai_model:
            cmd.extend(["--openai-model-name", self._openai_model])

        logging.info("Running Arbigent: %s", " ".join(cmd))
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
        except subprocess.TimeoutExpired:
            logging.error("Arbigent timed out after 600s for goal: %s", goal)
            final_state = self.get_post_transition_state()
            return base_agent.AgentInteractionResult(
                done=False,
                data={"raw_screenshot": final_state.pixels, "ui_elements": final_state.ui_elements},
            )
        logging.info("Arbigent exit code: %d", result.returncode)
        if result.stdout:
            logging.info("Arbigent stdout (last 1000 chars): %s", result.stdout[-1000:])
        if result.stderr:
            logging.warning("Arbigent stderr (last 1000 chars): %s", result.stderr[-1000:])

        final_state = self.get_post_transition_state()

        step_data = {
            "raw_screenshot": final_state.pixels,
            "ui_elements": final_state.ui_elements,
        }

        done = result.returncode == 0
        return base_agent.AgentInteractionResult(done=done, data=step_data)
