# Arbigent(Arbiter-Agent): An AI Agent Testing Framework for Modern Applications

**Zero to AI agent testing in minutes. Arbigent's intuitive UI and powerful code interface make it accessible to everyone, while its scenario breakdown feature ensures scalability for even the most complex tasks.**

> [!WARNING]
> There seems to be a spam account posing as Arbigent, but the account is not related to me. The creator's accounts are [`https://x.com/_takahirom_`](https://x.com/_takahirom_) and [`https://x.com/new_runnable`](https://x.com/new_runnable) .

## Screenshot

<img width="650" alt="arbigent-screenshot" src="https://github.com/user-attachments/assets/77ebfcb1-3a44-4eaf-9775-3dff2597f9d1" />

## Demo movie

https://github.com/user-attachments/assets/46fa3034-5671-4ff1-99df-abbdaebaf197

## Motivation

### Make AI Agent Testing Practical for Modern Applications

Traditional UI testing frameworks often struggle with modern applications because they are brittle and break easily when the UI changes. For instance, the introduction of A/B tests, updates to tutorials, or the appearance of unexpected dialogs can cause tests to fail.  
AI agents emerged as a solution, but testing with AI agents also presents challenges. AI agents often don't work as intended; for example, the agents might open other apps or click on the wrong button due to the complexity of the task.  
To address these challenges, I created Arbigent, an AI agent testing framework that can break down complex tasks into smaller, dependent scenarios. By decomposing tasks, Arbigent enables more predictable and scalable testing of AI agents in modern applications.

### Customizable for Various AI Providers, OSes, Form Factors, etc.

I believe many AI Agent testing frameworks will emerge in the future. However, widespread adoption might be delayed due to limitations in customization. For instance:

*   **Limited AI Provider Support:** Frameworks might be locked to specific AI providers, excluding those used internally by companies.
*   **Slow OS Adoption:** Support for different operating systems (like iOS and Android) could lag.
*   **Delayed Form Factor Support:** Expanding to form factors beyond phones, such as Android TV, might take considerable time.

To address these issues, I aimed to create a framework that empowers users with extensive customization capabilities. Inspired by [OkHttp's interceptor](https://square.github.io/okhttp/features/interceptors/) pattern, Arbigent provides interfaces for flexible customization, allowing users to adapt the framework to their specific needs, such as those listed above.

### Easy Integration into Development Workflows

Furthermore, I wanted to make Arbigent accessible to QA engineers by offering a user-friendly UI. This allows for scenario creation within the UI and seamless test execution via the code interface.

##  Key Feature Breakdown

**I. Core Functionality & Design**

*   **Complex Task Management:**
    *   **Scenario Dependencies:** Breaks down complex goals into smaller, manageable scenarios that depend on each other (e.g., login -> search).
    *   **Orchestration:** Acts as a mediator, managing the execution flow of AI agents across multiple, interconnected scenarios.
*   **Hybrid Development Workflow:**
    *   **UI-Driven Scenario Creation:**  Allows non-programmers (e.g., QA engineers) to visually design test scenarios through a user-friendly interface.
    *   **Code-Based Execution:** Enables software engineers to execute the saved scenarios programmatically (YAML files), allowing for integration with existing testing infrastructure.

**II. Cross-Platform & Device Support**

*   **Multi-Platform Compatibility:**
    *   **Mobile & TV:** Supports testing on iOS, Android, Web, and TV interfaces.
    *   **D-Pad Navigation:**  Handles TV interfaces that rely on D-pad navigation.

**III. AI Optimization & Efficiency**

*   **Enhanced AI Understanding:**
    *   **UI Tree Optimization:** Simplifies and filters the UI tree to improve AI comprehension and performance.
    *   **Accessibility-Independent:** Provides annotated screenshots to assist AI in understanding UIs that lack accessibility information.
*   **Cost Savings:**
    *   **Open Source:** Free to use, modify, and distribute, eliminating licensing costs.
    *   **Efficient Model Usage:** Compatible with cost-effective models like `GPT-4o mini`, reducing operational expenses.

**IV. Robustness & Reliability**

*   **Double Check with AI-Powered Image Assertion:** Integrates [Roborazzi's feature](https://takahirom.github.io/roborazzi/ai-powered-image-assertion.html#behavior-of-ai-powered-image-assertion) to verify AI decisions using image-based prompts and allows the AI to re-evaluate if needed.
*   **Stuck Screen Detection:** Identifies and recovers from situations where the AI agent gets stuck on the same screen, prompting it to reconsider its actions.

**V. Advanced Features & Customization**

*   **Flexible Code Interface:**
    *   **Custom Hooks:** Offers a code interface for adding custom initialization and cleanup methods, providing greater control over scenario execution.

**VI. Community & Open Source**

*   **Open Source Nature:**
    *   **Free & Open:** Freely available for use, modification, and distribution.
    *   **Community Driven:** Welcomes contributions from the community to enhance and expand the framework.

## Arbigent's Strengths and Weaknesses Based on [SMURF](https://testing.googleblog.com/2024/10/smurf-beyond-test-pyramid.html)

I categorized automated testing frameworks into five levels using the [SMURF](https://testing.googleblog.com/2024/10/smurf-beyond-test-pyramid.html) framework. Here's how Arbigent stacks up:

*   **Speed (1/5):** Arbigent's speed is currently limited by the underlying AI technology and the need to interact with the application's UI in real-time. This makes it slower than traditional unit or integration tests.
*   **Maintainability (4/5):** Arbigent excels in maintainability. You can write tests in natural language (e.g., "Complete the tutorial"), making them resilient to UI changes. The task decomposition feature also reduces duplication, further enhancing maintainability. Maintenance can be done by non-engineers, thanks to the natural language interface.
*   **Utilization (1/5):** Arbigent requires both device resources (emulators or physical devices) and AI resources, which can be costly. (AI cost can be around $0.005 per step and $0.02 per task when using GPT-4o.)
*   **Reliability (3/5):** Arbigent has several features to improve reliability. It automatically waits during loading screens, handles unexpected dialogs, and even attempts self-correction. However, external factors like emulator flakiness can still impact reliability.
    *   Recently I found Arbigent has retry feature and can execute the scenario from the beginning. But, even without retry, Arbigent works fine without failures thanks to the flexibility of AI.
*   **Fidelity (5/5):** Arbigent provides high fidelity by testing on real or emulated devices with the actual application. It can even assess aspects that were previously difficult to test, such as verifying video playback by checking for visual changes on the screen.

I believe that many of its current limitations, such as speed, maintainability, utilization, and reliability, will be addressed as AI technology continues to evolve. The need for extensive prompt engineering will likely diminish as AI models become more capable.

## How to Use

### Installation

Install the Arbigent UI binary from the [Release page](https://github.com/takahirom/arbigent/releases).
<img width="632" alt="image" src="https://github.com/user-attachments/assets/499da604-4a43-4eb8-b27f-325a83caa013" />


### Device Connection and AI API Key Entry

1. Connect your device to your PC.
2. In the Arbigent UI, select your connected device from the list of available devices. This will establish a connection.
3. Enter your AI provider's API key in the designated field within the Arbigent UI.

<img src="https://github.com/user-attachments/assets/77a002f5-8ab3-4cb1-94f6-6c15a223900c" width="450" alt="Device Connection and AI API Key Entry" />

### Scenario Creation

Use the intuitive UI to define scenarios. Simply specify the desired goal for the AI agent.

### Test Execution

Run tests either directly through the UI or programmatically via the code interface or CLI.

### CLI

You can install the CLI via Homebrew and run a saved YAML file.

<img width="600" alt="image" src="https://github.com/user-attachments/assets/f20fdac8-ae46-4dc7-aa2b-6fc0b5bd4a9d" />

```bash
brew tap takahirom/homebrew-repo
brew install takahirom/repo/arbigent
```

```
Usage: arbigent [<options>]

Options for OpenAI API AI:
  --open-ai-endpoint=<text>    Endpoint URL (default:
                               https://api.openai.com/v1/)
  --open-ai-model-name=<text>  Model name (default: gpt-4o-mini)

Options for Gemini API AI:
  --gemini-endpoint=<text>    Endpoint URL (default:
                              https://generativelanguage.googleapis.com/v1beta/openai/)
  --gemini-model-name=<text>  Model name (default: gemini-1.5-flash)

Options for Azure OpenAI:
  --azure-open-aiendpoint=<text>     Endpoint URL
  --azure-open-aiapi-version=<text>  API version
  --azure-open-aimodel-name=<text>   Model name (default: gpt-4o-mini)

Options:
  --ai-type=(openai|gemini|azureopenai)  Type of AI to use
  --os=(android|ios|web)                 Target operating system
  --project-file=<text>                 Path to the scenario YAML file
  -h, --help                             Show this message and exit
```

### Minimal GitHub Actions CI sample

You can use the CLI in GitHub Actions like in this sample. There are only two files: `.github/workflows/arbigent-test.yaml` and `arbigent-project.yaml`. This example demonstrates GitHub Actions and an `arbigent-project.yaml` file created by the Arbigent UI.

https://github.com/takahirom/arbigent-sample

## Supported AI Providers

| AI Provider | Supported |
|-------------|-----------|
| OpenAI      | Yes       |
| Gemini      | Yes       |
| OpenAI based APIs like Ollama | Yes |

You can add AI providers by implementing the `ArbigentAi` interface.

## Supported OSes / Form Factors

| OS          | Supported | Test Status in the Arbigent repository            |
|-------------|-----------|---------------------------------------------------|
| Android     | Yes       | End-to-End including Android emulator and real AI |
| iOS         | Yes       | End-to-End including iOS simulator and real AI    |
| Web(Chrome) | Yes       | Currently, Testing not yet conducted              |

You can add OSes by implementing the `ArbigentDevice` interface. Thanks to the excellent [Maestro](https://github.com/mobile-dev-inc/maestro) library, we are able to support multiple OSes.

| Form Factor    | Supported |
|----------------|-----------|
| Phone / Tablet | Yes       |
| TV(D-Pad)      | Yes       |

# Learn More

## Basic Structure

### Execution Flow

The execution flow involves the UI, Arbigent, ArbigentDevice, and ArbigentAi. The UI sends a project creation request to Arbigent, which fetches the UI tree from ArbigentDevice. ArbigentAi then decides on an action based on the goal and UI tree. The action is performed by ArbigentDevice, and the results are returned to the UI for display.

```mermaid
sequenceDiagram
  participant UI(or Tests)
  participant ArbigentAgent
  participant ArbigentDevice
  participant ArbigentAi
  UI(or Tests)->>ArbigentAgent: Execute
  loop
    ArbigentAgent->>ArbigentDevice: Fetch UI tree
    ArbigentDevice->>ArbigentAgent: Return UI tree
    ArbigentAgent->>ArbigentAi: Decide Action by goal and UI tree and histories
    ArbigentAi->>ArbigentAgent: Return Action
    ArbigentAgent->>ArbigentDevice: Perform actions
    ArbigentDevice->>ArbigentAgent: Return results
  end
  ArbigentAgent->>UI(or Tests): Display results
```

###  Class Diagram

The class diagram illustrates the relationships between ArbigentProject, ArbigentScenario, ArbigentTask, ArbigentAgent, ArbigentScenarioExecutor, ArbigentAi, ArbigentDevice, and ArbigentInterceptor.

```mermaid
classDiagram
  direction TB
  class ArbigentProject {
    +List~ArbigentScenario~ scenarios
    +execute()
  }
  class ArbigentAgentTask {
    +String goal
  }
  class ArbigentAgent {
    +ArbigentAi ai
    +ArbigentDevice device
    +List~ArbigentInterceptor~ interceptors
    +execute(arbigentAgentTask)
  }
  class ArbigentScenarioExecutor {
    +execute(arbigentScenario)
  }
  class ArbigentScenario {
    +List~ArbigentAgentTask~ agentTasks
  }
  ArbigentProject o--"*" ArbigentScenarioExecutor
ArbigentScenarioExecutor o--"*" ArbigentAgent
ArbigentScenario o--"*" ArbigentAgentTask
ArbigentProject o--"*" ArbigentScenario
```

### Saved project file

> [!WARNING]
> The yaml format is still under development and may change in the future.

The project file is saved in YAML format and contains scenarios with goals, initialization methods, and cleanup data. Dependencies between scenarios are also defined.
You can write a project file in YAML format by hand or create it using the Arbigent UI.

The id is auto-generated UUID by Arbigent UI but you can change it to any string.

```yaml
scenarios:
  - id: "7788d7f4-7276-4cb3-8e98-7d3ad1d1cd47"
    goal: "Open the Now in Android app from the app list. The goal is to view the list\
    \ of topics.  Do not interact with the app beyond this."
    initializeMethods:
      type: "LaunchApp"
      packageName: "com.google.samples.apps.nowinandroid"
    cleanupData:
      type: "Cleanup"
      packageName: "com.google.samples.apps.nowinandroid"
  - id: "f0ef0129-c764-443f-897d-fc4408e5952b"
    goal: "In the Now in Android app, select an tech topic and complete the form in\
    \ the \"For you\" tab. The goal is reached when articles are displayed.  Do not\
    \ click on any articles. If the browser opens, return to the app."
    dependency: "7788d7f4-7276-4cb3-8e98-7d3ad1d1cd47"
    imageAssertions:
      - assertionPrompt: "Articles should be visible on the screen"
```

## Code Interface

> [!WARNING]
> The code interface is still under development and may change in the future.

Arbigent provides a code interface for executing tests programmatically. Here's an example of how to run a test:


### Dependency

Stay tuned for the release of Arbigent on Maven Central.

### Running saved project yaml file

You can load a project yaml file and execute it using the following code:

```kotlin
class ArbigentTest {
  private val scenarioFile = File(this::class.java.getResource("/projects/nowinandroidsample.yaml").toURI())

  @Test
  fun tests() = runTest(
    timeout = 10.minutes
  ) {
    val arbigentProject = ArbigentProject(
      file = scenarioFile,
      aiFactory = {
        OpenAIAi(
          apiKey = System.getenv("OPENAI_API_KEY")
        )
      },
      deviceFactory = {
        AvailableDevice.Android(
          dadb = Dadb.discover()!!
        ).connectToDevice()
      }
    )
    arbigentProject.execute()
  }
}
```

### Run a scenario directly

```kotlin
val agentConfig = AgentConfig {
  deviceFactory { FakeDevice() }
  ai(FakeAi())
}
val arbigentScenarioExecutor = ArbigentScenarioExecutor {
}
val arbigentScenario = ArbigentScenario(
  id = "id2",
  agentTasks = listOf(
    ArbigentAgentTask("id1", "Login in the app and see the home tab.", agentConfig),
    ArbigentAgentTask("id2", "Search an episode and open detail", agentConfig)
  ),
  maxStepCount = 10,
)
arbigentScenarioExecutor.execute(
  arbigentScenario
)
```

### Run a goal directly

```kotlin
val agentConfig = AgentConfig {
  deviceFactory { FakeDevice() }
  ai(FakeAi())
}

val task = ArbigentAgentTask("id1", "Login in the app and see the home tab.", agentConfig)
ArbigentAgent(agentConfig)
  .execute(task)
```
