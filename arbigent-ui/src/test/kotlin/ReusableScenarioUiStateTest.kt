package io.github.takahirom.arbigent.ui

import io.github.takahirom.arbigent.*
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReusableScenarioUiStateTest {

  @Before
  fun setup() {
    globalKeyStoreFactory = TestKeyStoreFactory()
  }

  private fun holderOf(content: ArbigentScenarioContent) =
    ArbigentScenarioStateHolder(id = content.id, tagManager = ArbigentTagManager()).apply {
      load(content)
    }

  @Test
  fun `call-form content round-trips through the state holder`() {
    val content = ArbigentScenarioContent(
      id = "combo",
      steps = listOf(
        ArbigentScenarioContent.ReusableStep(uses = "login", withValues = mapOf("user" to "paid")),
        ArbigentScenarioContent.ReusableStep(uses = "play-premium-content"),
      ),
    )
    val holder = holderOf(content)
    assertTrue(holder.reusableStepsModeStateFlow.value)

    val recreated = holder.createArbigentScenarioContent()
    assertTrue(recreated.isCallForm())
    assertEquals(content.callSteps(), recreated.callSteps())
    assertEquals("", recreated.goal)
  }

  @Test
  fun `single uses sugar round-trips through the state holder`() {
    val content = ArbigentScenarioContent(
      id = "call-login",
      uses = "login",
      withValues = mapOf("user" to "paid"),
    )
    val holder = holderOf(content)
    val recreated = holder.createArbigentScenarioContent()
    // Single-entry steps are written back using the uses sugar.
    assertEquals("login", recreated.uses)
    assertEquals(mapOf("user" to "paid"), recreated.withValues)
    assertEquals(emptyList<ArbigentScenarioContent.ReusableStep>(), recreated.steps)
  }

  @Test
  fun `reusable inputs round-trip through the state holder`() {
    val content = ArbigentScenarioContent(
      id = "login",
      goal = "Log in as {{inputs.user}}",
      inputs = mapOf(
        "user" to ArbigentScenarioContent.ReusableInput(required = true),
        "method" to ArbigentScenarioContent.ReusableInput(default = "email"),
      ),
    )
    val holder = holderOf(content)
    val recreated = holder.createArbigentScenarioContent()
    assertEquals(content.inputs, recreated.inputs)
    assertEquals(content.goal, recreated.goal)
  }

  @Test
  fun `make this reusable moves content and converts scenario to call node`() {
    val appStateHolder = ArbigentAppStateHolder(aiFactory = { FakeAi() })
    val holder = ArbigentScenarioStateHolder(id = "login-test", tagManager = ArbigentTagManager()).apply {
      load(
        ArbigentScenarioContent(
          id = "login-test",
          goal = "Log in as paid user",
          maxStep = 25,
          initializationMethods = listOf(ArbigentScenarioContent.InitializationMethod.Back(2)),
        )
      )
    }

    appStateHolder.makeScenarioReusable(holder, "login-part")

    val reusable = appStateHolder.getReusableScenarioById("login-part")!!
    assertEquals("Log in as paid user", reusable.goal)
    assertEquals(25, reusable.maxStep)
    assertEquals(
      listOf<ArbigentScenarioContent.InitializationMethod>(
        ArbigentScenarioContent.InitializationMethod.Back(2)
      ),
      reusable.initializationMethods
    )

    // The original scenario keeps its id and becomes a call node.
    assertEquals("login-test", holder.id)
    assertTrue(holder.reusableStepsModeStateFlow.value)
    val converted = holder.createArbigentScenarioContent()
    assertEquals("login-part", converted.callSteps().single().uses)
    assertEquals("", converted.goal)
    assertTrue(converted.initializationMethods.isEmpty())
  }

  @Test
  fun `reusable scenario references are detected for delete protection`() {
    val appStateHolder = ArbigentAppStateHolder(aiFactory = { FakeAi() })
    appStateHolder.addReusableScenario(ArbigentScenarioContent(id = "part", goal = "goal"))
    appStateHolder.addReusableScenario(
      ArbigentScenarioContent(id = "composite", steps = listOf(ArbigentScenarioContent.ReusableStep(uses = "part")))
    )
    assertEquals(listOf("composite"), appStateHolder.reusableScenarioReferences("part"))
    assertEquals(emptyList<String>(), appStateHolder.reusableScenarioReferences("composite"))
  }
}
