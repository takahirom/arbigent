package io.github.takahirom.arbigent.result

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlComment
import com.charleskorn.kaml.YamlConfiguration
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
public class ArbigentProjectExecutionResult(
  public val scenarios: List<ArbigentScenarioResult>,
) {
  public companion object {
    public val yaml: Yaml = Yaml(
      configuration = YamlConfiguration(
        strictMode = false,
        polymorphismStyle = PolymorphismStyle.Property
      )
    )
  }
}

@Serializable
public class ArbigentScenarioResult(
  public val id: String,
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
  public val screenshotFilePath: String,
  public val agentCommand: String?,
  public val uiTreeStrings: ArbigentUiTreeStrings?,
  public val aiRequest: String?,
  public val aiResponse: String?
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