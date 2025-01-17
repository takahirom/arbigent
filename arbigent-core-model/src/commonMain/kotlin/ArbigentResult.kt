package io.github.takahirom.arbigent.result

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlComment
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
public data class ArbigentProjectExecutionResult(
  public val scenarios: List<ArbigentScenarioResult>,
) {
  public companion object {
    public val yaml: Yaml = Yaml(
      configuration = YamlConfiguration(
        encodeDefaults = false,
        strictMode = false,
        polymorphismStyle = PolymorphismStyle.Property
      )
    )
  }
}

@Serializable
public data class ArbigentScenarioResult(
  public val id: String,
  public val goal: String? = null,
  public val executionStatus: String? = null,
  public val isSuccess: Boolean,
  public val histories: List<ArbigentAgentResults>,
)

@Serializable
public data class ArbigentAgentResults(
  @YamlComment
  public val status:String,
  public val agentResults: List<ArbigentAgentResult>,
) {
  public fun startTimestamp(): Long? = agentResults.firstOrNull()?.startTimestamp
  public fun endTimestamp(): Long? = agentResults.lastOrNull()?.endTimestamp
}

@Serializable
public data class ArbigentAgentResult(
  public val goal: String,
  public val maxStep: Int = 10,
  public val deviceFormFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Mobile,
  public val isGoalArchived: Boolean,
  public val steps: List<ArbigentAgentTaskStepResult>,
  public val deviceName: String,
  public val startTimestamp: Long? = null,
  public val endTimestamp: Long?,
)

@Serializable
public data class ArbigentAgentTaskStepResult(
  public val summary: String,
  public val screenshotFilePath: String,
  public val agentCommand: String?,
  public val uiTreeStrings: ArbigentUiTreeStrings?,
  public val aiRequest: String?,
  public val aiResponse: String?,
  public val timestamp: Long
)

@Serializable
public data class ArbigentUiTreeStrings(
  val allTreeString: String,
  val optimizedTreeString: String,
)

@Serializable
public sealed interface ArbigentScenarioDeviceFormFactor {
  @Serializable
  @SerialName("Mobile")
  public data object Mobile : ArbigentScenarioDeviceFormFactor

  @Serializable
  @SerialName("Tv")
  public data object Tv : ArbigentScenarioDeviceFormFactor

  public fun isMobile(): Boolean = this == Mobile
  public fun isTv(): Boolean = this is Tv
}