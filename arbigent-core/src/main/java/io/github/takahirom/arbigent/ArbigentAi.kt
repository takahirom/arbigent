package io.github.takahirom.arbigent

import io.github.takahirom.arbigent.result.ArbigentScenarioDeviceFormFactor
import io.github.takahirom.arbigent.result.ArbigentUiTreeStrings
import kotlinx.serialization.Serializable

@Serializable
public data class GeneratedScenariosContent(
  val scenarios: List<ArbigentScenarioContent>
)

public interface ArbigentAi {
  public data class DecisionInput(
    val stepId: String,
    val contextHolder: ArbigentContextHolder,
    val formFactor: ArbigentScenarioDeviceFormFactor,
    val uiTreeStrings: ArbigentUiTreeStrings,
    // Only true if it is TV form factor
    val focusedTreeString: String?,
    val agentActionTypes: List<AgentActionType>,
    val screenshotFilePath: String,
    val apiCallJsonLFilePath: String,
    val elements: ArbigentElementList,
    val prompt: ArbigentPrompt,
    val cacheKey: String,
    val aiOptions: ArbigentAiOptions?,
    val mcpTools: List<MCPTool>? = null,
  )

  public data class ScenarioGenerationInput(
    val scenariosToGenerate: String,
    val appUiStructure: String,
    val customInstruction: String,
    val scenariosToBeUsedAsContext: List<ArbigentScenarioContent>
  )

  public fun generateScenarios(
    scenarioGenerationInput: ScenarioGenerationInput
  ): GeneratedScenariosContent
  @Serializable
  public data class DecisionOutput(
    val agentActions: List<ArbigentAgentAction>,
    val step: ArbigentContextHolder.Step,
  )
  public class FailedToParseResponseException(message: String, cause: Throwable) : Exception(message, cause)
  public fun decideAgentActions(
    decisionInput: DecisionInput
  ): DecisionOutput

  public data class ImageAssertionInput(
    val ai: ArbigentAi,
    val arbigentContextHolder: ArbigentContextHolder,
    val screenshotFilePaths: List<String>,
    val assertions: ArbigentImageAssertions,
  )
  public data class ImageAssertionOutput(
    val results: List<ImageAssertionResult>
  )

  public class ImageAssertionResult(
    public val assertionPrompt: String,
    public val isPassed: Boolean,
    public val fulfillmentPercent: Int,
    public val explanation: String?,
  )
  public fun assertImage(
    imageAssertionInput: ImageAssertionInput
  ): ImageAssertionOutput

  public enum class JsonSchemaType {
    OpenAI,
    GeminiOpenAICompatible;
  }

  public fun jsonSchemaType(): JsonSchemaType = JsonSchemaType.OpenAI

}
