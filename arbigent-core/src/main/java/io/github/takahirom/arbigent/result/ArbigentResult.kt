package io.github.takahirom.arbigent.result

import com.charleskorn.kaml.YamlComment
import io.github.takahirom.arbigent.ArbigentScenarioDeviceFormFactor
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable


@Serializable
public class ArbigentProjectExecutionResult(
  public val scenarioAssignmentResults: List<ArbigentScenarioResult>,
)

@Serializable
public class ArbigentScenarioResult(
  public val scenarioId: String,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  public val goal: String? = null,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  public val executionStatus: String? = null,
  public val isSuccess: Boolean,
  public val histories: List<ArbigentAgentResults>,
)

@Serializable
public class ArbigentAgentResults(
  @YamlComment
  public val status:String,
  public val agentResult: List<ArbigentAgentResult>,
)

@Serializable
public class ArbigentAgentResult(
  public val goal: String,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  public val maxStep: Int = 10,
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  public val deviceFormFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
  public val isGoalArchived: Boolean,
  public val steps: List<ArbigentAgentTaskStepResult>,
)

@Serializable
public class ArbigentAgentTaskStepResult(
  public val summary: String,
  public val screenshotFilePath: String
)