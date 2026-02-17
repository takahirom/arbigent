package io.github.takahirom.arbigent.result

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlComment
import com.charleskorn.kaml.YamlConfiguration
import io.github.takahirom.arbigent.ArbigentContentTags
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
public data class ArbigentProjectExecutionResult(
  public val scenarios: List<ArbigentScenarioResult>,
  public val stepFeedbacks: List<StepFeedback> = listOf(),
) {
  public fun startTimestamp(): Long? = scenarios.firstOrNull()?.startTimestamp()
  public fun endTimestamp(): Long? = scenarios.lastOrNull()?.endTimestamp()
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

public sealed interface StepFeedbackEvent {
  public data class RemoveGood(val stepId: String): StepFeedbackEvent
  public data class RemoveBad(val stepId: String): StepFeedbackEvent
}

@Serializable
public sealed interface StepFeedback: StepFeedbackEvent {
  @Serializable
  @SerialName("Good")
  public data class Good(
    val stepId: String,
    val reason: String? = null
  ): StepFeedback

  @Serializable
  @SerialName("Bad")
  public data class Bad(
    val stepId: String,
    val reason: String? = null
  ): StepFeedback
}


@Serializable
public data class ArbigentScenarioResult(
  public val id: String,
  public val goal: String? = null,
  public val tags: ArbigentContentTags = setOf(),
  public val executionStatus: String? = null,
  public val isSuccess: Boolean,
  public val histories: List<ArbigentAgentResults>,
) {
  public fun startTimestamp(): Long? = histories.firstOrNull()?.startTimestamp()
  public fun endTimestamp(): Long? = histories.lastOrNull()?.endTimestamp()
}

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
  public val deviceFormFactor: ArbigentScenarioDeviceFormFactor = ArbigentScenarioDeviceFormFactor.Unspecified,
  public val isGoalAchieved: Boolean,
  public val steps: List<ArbigentAgentTaskStepResult>,
  public val deviceName: String,
  public val startTimestamp: Long? = null,
  public val endTimestamp: Long?,
)

@Serializable
public data class ArbigentAgentTaskStepResult(
  public val stepId: String,
  public val summary: String,
  public val screenshotFilePath: String,
  public val apiCallJsonPath: String?,
  public val agentAction: String?,
  // UiTree is too big to store in the yaml file.
//  public val uiTreeStrings: ArbigentUiTreeStrings?,
  public val timestamp: Long,
  public val cacheHit: Boolean
)

@Serializable
public data class ArbigentUiTreeStrings(
  val allTreeString: String,
  val optimizedTreeString: String,
  val aiHints: List<String> = emptyList(),
)

@Serializable
public sealed interface ArbigentScenarioDeviceFormFactor {
  @Serializable
  @SerialName("Mobile")
  public data object Mobile : ArbigentScenarioDeviceFormFactor

  @Serializable
  @SerialName("Tv")
  public data object Tv : ArbigentScenarioDeviceFormFactor

  @Serializable
  @SerialName("Unspecified")
  public data object Unspecified : ArbigentScenarioDeviceFormFactor

  public fun isMobile(): Boolean = this == Mobile
  public fun isTv(): Boolean = this is Tv
  public fun isUnspecified(): Boolean = this is Unspecified
}
