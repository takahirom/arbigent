package io.github.takahirom.arbigent

public interface ArbigentAi {
  public data class DecisionInput(
    val arbigentContextHolder: ArbigentContextHolder,
    val formFactor: ArbigentScenarioDeviceFormFactor,
    val uiTreeStrings: ArbigentUiTreeStrings,
    // Only true if it is TV form factor
    val focusedTreeString: String?,
    val agentCommandTypes: List<AgentCommandType>,
    val screenshotFilePath: String,
    val elements: ArbigentElementList,
  )
  public data class DecisionOutput(
    val agentCommands: List<ArbigentAgentCommand>,
    val step: ArbigentContextHolder.Step
  )
  public class FailedToParseResponseException(message: String, cause: Throwable) : Exception(message, cause)
  public fun decideAgentCommands(
    decisionInput: DecisionInput
  ): DecisionOutput

  public data class ImageAssertionInput(
    val ai: ArbigentAi,
    val arbigentContextHolder: ArbigentContextHolder,
    val screenshotFilePath: String,
    val assertions: List<ArbigentImageAssertion>,
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
}
