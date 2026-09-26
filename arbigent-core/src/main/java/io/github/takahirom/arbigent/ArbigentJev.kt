package io.github.takahirom.arbigent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Lets a Jev-compatible decision model (TypeSafe's System One API) pick the next action when it is
 * confident, so the step skips the LLM call. Anything below the thresholds goes to the LLM.
 */
@Serializable
public data class ArbigentJevSettings(
  public val mode: ArbigentJevMode = ArbigentJevMode.Active,
  // Jev's confidence is compressed, so this is a per-project tuning knob, not a probability.
  public val actionThreshold: Double = DefaultActionThreshold,
  // Absent means goal_achieved always goes to the LLM.
  public val goalThreshold: Double? = null,
) {
  init {
    require(actionThreshold in 0.0..1.0) { "jev actionThreshold must be within 0..1: $actionThreshold" }
    require(goalThreshold == null || goalThreshold in 0.0..1.0) { "jev goalThreshold must be within 0..1: $goalThreshold" }
  }

  public fun withOverrides(overrides: ArbigentJevOverrides): ArbigentJevSettings = copy(
    mode = overrides.mode ?: mode,
    actionThreshold = overrides.actionThreshold ?: actionThreshold,
    goalThreshold = overrides.goalThreshold ?: goalThreshold,
  )

  public fun description(): String =
    "$mode (action >= $actionThreshold, goal ${goalThreshold?.let { ">= $it" } ?: "always by the AI"})"

  public companion object {
    public const val DefaultActionThreshold: Double = 0.85
  }
}

@Serializable
public enum class ArbigentJevMode {
  Disabled,

  // Jev is asked alongside the AI on every step and only logged; the AI always acts.
  Shadow,
  Active,
}

/** Per-run overrides, such as CLI flags, on top of the project's [ArbigentJevSettings]. */
public data class ArbigentJevOverrides(
  public val mode: ArbigentJevMode? = null,
  public val actionThreshold: Double? = null,
  public val goalThreshold: Double? = null,
) {
  public fun isEmpty(): Boolean = mode == null && actionThreshold == null && goalThreshold == null
}

public fun interface ArbigentJevClient {
  /** Posts a System One request (`state`, `questions`) and returns the response body. */
  public suspend fun decide(request: JsonObject): JsonObject
}

public class ArbigentJevHttpClient(
  private val apiKey: String,
  public val baseUrl: String = DefaultBaseUrl,
  public val model: String = DefaultModel,
) : ArbigentJevClient {
  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
  private val json = Json { ignoreUnknownKeys = true }

  init {
    require(apiKey.isNotBlank()) { "Jev API key is blank" }
    ConfidentialInfo.addStringToBeRemoved(apiKey, "{{JEV_API_KEY}}")
  }

  override suspend fun decide(request: JsonObject): JsonObject {
    val body = JsonObject(request + ("model" to JsonPrimitive(model)))
    val httpRequest = HttpRequest.newBuilder(URI(baseUrl.trimEnd('/') + SystemOnePath))
      .timeout(Duration.ofSeconds(10))
      .header("Authorization", "Bearer $apiKey")
      .header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
      .build()
    val response = withContext(Dispatchers.IO) {
      http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
    }
    check(response.statusCode() == 200) {
      "Jev returned HTTP ${response.statusCode()}: ${response.body().take(300)}"
    }
    return json.parseToJsonElement(response.body()).jsonObject
  }

  public companion object {
    // The TypeSafe SDK's defaults and environment variable names, so a key set up for the SDK works here.
    public const val DefaultBaseUrl: String = "https://api.typesafe.ai"
    public const val DefaultModel: String = "jev-latest"
    public const val ApiKeyEnv: String = "TYPESAFE_API_KEY"
    public const val BaseUrlEnv: String = "TYPESAFE_BASE_URL"
    public const val ModelEnv: String = "TYPESAFE_DEFAULT_MODEL"
    private const val SystemOnePath = "/v1/systemone"
  }
}

/** What an agent needs to consult Jev: the effective settings and a client to call. */
public class ArbigentJevConfig(
  public val settings: ArbigentJevSettings,
  public val client: ArbigentJevClient,
) {
  public companion object {
    /** The settings Jev runs with, or null when the project and [overrides] leave it off. */
    public fun effectiveSettings(
      projectSettings: ArbigentJevSettings?,
      overrides: ArbigentJevOverrides,
    ): ArbigentJevSettings? {
      if (projectSettings == null && overrides.isEmpty()) return null
      return (projectSettings ?: ArbigentJevSettings()).withOverrides(overrides)
        .takeIf { it.mode != ArbigentJevMode.Disabled }
    }

    /**
     * Null when Jev should not run: it is off, or no [client] is configured. A missing key never
     * fails the run, so projects that enable Jev still run on machines and CI jobs without one.
     */
    public fun resolve(
      projectSettings: ArbigentJevSettings?,
      overrides: ArbigentJevOverrides,
      client: ArbigentJevClient?,
    ): ArbigentJevConfig? {
      val settings = effectiveSettings(projectSettings, overrides) ?: return null
      return ArbigentJevConfig(settings, client ?: return null)
    }
  }
}
