# Arbigent - AI-Powered QA Testing Setup Guide

## What is Arbigent?

Arbigent is an AI agent that tests your app by looking at the screen and deciding what to tap — just like a human tester would. Instead of writing brittle UI selectors and step-by-step scripts, you describe **what you want tested in plain English**, and the AI figures out how to do it.

**Example:** Instead of writing `tapOn("More") -> tapOn("Settings")`, you just write:

```
goal: "Open the ESPN app, tap on More, then go to Settings"
```

The AI sees the screen, finds the right buttons, and taps them — even if the UI changes.

## Current Setup

| Component       | Details                            |
|-----------------|------------------------------------|
| **Project**     | Arbigent v0.72.0-SNAPSHOT          |
| **Java**        | JDK 17                             |
| **AI Provider** | OpenAI (GPT-4.1)                   |
| **Target OS**   | Android                            |
| **Test Device** | Physical device (26151FDF6005TG)   |
| **Test App**    | ESPN (`com.espn.score_center.debug`)|

## Prerequisites

1. **Java 17+** installed
2. **Android device or emulator** connected via USB/ADB
3. **OpenAI API key** (GPT-4.1 model)
4. **ADB** available in your PATH

Verify your setup:
```bash
java -version          # Should show 17+
adb devices            # Should show your device
```

## Quick Start (5 Steps)

### Step 1: Clone and build

```bash
git clone <repo-url>
cd arbigent
./gradlew installDist
```

### Step 2: Configure your API key

Create the file `.arbigent/settings.local.yml`:

```yaml
project-file: .arbigent/espn-project.yml
ai-type: openai
openai-api-key: YOUR_OPENAI_API_KEY_HERE
os: android
```

> This file is gitignored, so your key stays local.

### Step 3: Create a test scenario

Create the file `.arbigent/espn-project.yml`:

```yaml
scenarios:
  - id: "espn-open-more-settings"
    goal: "Open the ESPN app, tap on 'More' in the bottom navigation, then go to 'Settings'."
    initializationMethods:
      - type: "LaunchApp"
        packageName: "com.espn.score_center.debug"
    tags:
      - name: "ESPN"

  - id: "espn-search-lakers"
    goal: "Search for Lakers and open the team page"
    initializationMethods:
      - type: "LaunchApp"
        packageName: "com.espn.score_center.debug"
    tags:
      - name: "ESPN"

settings:
  aiOptions:
    temperature: 0.0
```

### Step 4: Run a test

```bash
./arbigent-cli/build/install/arbigent/bin/arbigent run --scenario-ids="espn-open-more-settings"
```

### Step 5: View results

```bash
open arbigent-result/report.html
```

Results are saved in `arbigent-result/`:
- `report.html` — Visual step-by-step report with screenshots
- `result.yml` — Machine-readable results
- `screenshots/` — Screenshot at each AI decision step
- `jsonls/` — Raw API call logs

## How It Works (Technical Deep Dive)

### The Execution Loop

```
You write:  goal: "Search for Lakers and open the team page"

    +----------------------------------------------------------+
    |  1. CAPTURE SCREEN                                       |
    |     - Take screenshot (PNG)                              |
    |     - Fetch UI view hierarchy from device via Maestro    |
    |                                                          |
    |  2. PROCESS UI TREE                                      |
    |     - Filter out invisible/system elements               |
    |     - Optimize tree (remove noise, prune empty nodes)    |
    |     - Index every clickable/focusable element:           |
    |         0: ImageButton(id=search_btn, text=Search)       |
    |         1: TextView(text=Home, selected=true)            |
    |         2: TextView(text=Scores, clickable=true)         |
    |         3: TextView(text=More, clickable=true)           |
    |                                                          |
    |  3. BUILD PROMPT FOR AI                                  |
    |     - Goal: your plain English description               |
    |     - Previous steps: what the AI already did            |
    |     - Screenshot: the current screen image               |
    |     - Elements: the indexed list above                   |
    |                                                          |
    |  4. AI DECIDES NEXT ACTION                               |
    |     - AI returns a structured action (e.g., click #3)    |
    |                                                          |
    |  5. EXECUTE ACTION ON DEVICE                             |
    |     - Translates action to Maestro command               |
    |     - Maestro sends ADB tap/input to device              |
    |                                                          |
    |  6. CHECK RESULT                                         |
    |     - GoalAchieved? -> Done (pass)                       |
    |     - Failed? -> Done (fail)                             |
    |     - Otherwise -> Go back to step 1                     |
    +----------------------------------------------------------+
```

### Screen Detection: How Arbigent "Sees" the App

Arbigent uses two sources of information simultaneously:

**1. Screenshot (visual)**
A PNG screenshot is taken via Maestro at each step. This is sent to the AI as an image so it can visually understand the screen layout.

**2. UI Element Tree (structural)**
Maestro fetches the device's accessibility/view hierarchy — a tree of every UI element with attributes like text, ID, clickable state, bounds, etc.

The raw tree is **optimized** before being sent to the AI:
- Invisible elements (zero width/height) are removed
- System UI (status bar) is filtered out
- Nodes with no meaningful content are pruned
- Single-child wrappers are collapsed

Each remaining clickable/focusable element gets an **index number**:

```
0: ImageButton(id=search_btn, accessibilityText=Search, clickable=true)
1: TextView(text=Home, selected=true, clickable=true)
2: TextView(text=Scores, clickable=true)
3: TextView(text=More, clickable=true)
4: FrameLayout(id=bottom_nav, clickable=true)
```

The AI refers to elements by their index when deciding what to click.

### The AI Prompt: What the AI Receives Each Step

Every step, the AI gets a structured prompt like this:

```xml
<GOAL>Open the ESPN app, tap on 'More', then go to Settings</GOAL>

<STEP>
Current step: 2
Step limit: 10

<PREVIOUS_STEPS>
Step 1.
image description: ESPN home screen with bottom nav showing Home, Scores, More tabs.
memo: Need to tap the More button in the bottom navigation.
action done: Click on index: 3
</PREVIOUS_STEPS>
</STEP>

<UI_STATE>
Please refer to the image.
<ELEMENTS>
index:element
0: ImageButton(id=search_btn, accessibilityText=Search, clickable=true)
1: TextView(text=Account, clickable=true)
2: TextView(text=Settings, clickable=true)
3: TextView(text=App Info, clickable=true)
...
</ELEMENTS>
</UI_STATE>

<INSTRUCTIONS>
Based on the above, decide on the next action to achieve the goal.
Please ensure not to repeat the same action.
</INSTRUCTIONS>
```

Plus the screenshot image is attached. The AI sees both the visual screenshot AND the text element list.

### AI Actions: What the AI Can Do

The AI responds with a structured JSON action. Available actions:

| Action | What it does | Example |
|--------|-------------|---------|
| `ClickWithIndex` | Tap an element by its index number | AI says `index: 3` -> calculates center pixel `(972, 2200)` -> Maestro taps that point |
| `InputText` | Type text into the focused field | AI says `text: "Lakers"` -> Maestro types it |
| `Scroll` | Scroll down the screen | Maestro sends scroll command |
| `BackPress` | Press the device back button | Maestro sends back press |
| `KeyPress` | Press a specific key (ENTER, TAB, etc.) | Maestro sends key event |
| `Wait` | Wait for a duration (e.g., loading) | Thread sleeps for N ms |
| `GoalAchieved` | AI declares the goal is done | Scenario passes |
| `Failed` | AI declares the goal is unreachable | Scenario fails with reason |

### How Clicking Works (Coordinates)

When the AI decides `ClickWithIndex(3)`:

1. Look up element 3's bounds: `x=900, y=2150, width=144, height=100`
2. Calculate center: `centerX = 900 + 144/2 = 972`, `centerY = 2150 + 100/2 = 2200`
3. Send to Maestro: `TapOnPointV2Command(point = "972,2200")`
4. Maestro translates to ADB tap (Android) or XCTest tap (iOS)

### Stuck Screen Detection

If the screenshot is **identical** to the previous step (the action had no visible effect), the AI gets extra feedback:

> "Failed to produce the intended outcome. The current screen is identical to the previous one. Please try other actions."

This prevents the AI from repeating the same ineffective action in a loop.

### Step History & Memory

The AI receives its full action history in each prompt (`<PREVIOUS_STEPS>`). Each step records:
- **image description**: AI's description of what it saw
- **memo**: AI's reasoning about what to do next
- **action done**: The action that was executed
- **feedback**: Any error or stuck-screen messages

This gives the AI context to make better decisions and avoid repeating failed actions.

## Writing Scenarios

A scenario is just a YAML block with 3 parts:

```yaml
- id: "unique-name"                        # ID to reference this scenario
  goal: "Describe what to test in English"  # The AI reads this
  initializationMethods:                    # How to start
    - type: "LaunchApp"
      packageName: "com.your.app"
```

### Chaining Scenarios (Dependencies)

For multi-step flows, chain scenarios together:

```yaml
scenarios:
  - id: "login"
    goal: "Log in with test credentials"
    initializationMethods:
      - type: "LaunchApp"
        packageName: "com.your.app"

  - id: "navigate-to-profile"
    goal: "Go to the user profile page"
    dependency: "login"                     # Runs after "login" completes
```

### Initialization Methods

| Type           | What it does                    | Example                              |
|----------------|---------------------------------|--------------------------------------|
| `LaunchApp`    | Opens an app                    | `packageName: "com.espn.score_center.debug"` |
| `CleanupData`  | Clears app data before launch   | `packageName: "com.your.app"`        |
| `Back`         | Presses back button N times     | `times: 5`                           |
| `Wait`         | Waits for a duration            | `durationMs: 2000`                   |
| `OpenLink`     | Opens a URL                     | `link: "https://example.com"`        |

## CLI Commands

```bash
# Alias for convenience (add to your .bashrc/.zshrc)
alias arbigent='./arbigent-cli/build/install/arbigent/bin/arbigent'

# See all options
arbigent --help

# List all scenarios in the project
arbigent scenarios

# Run a specific scenario
arbigent run --scenario-ids="espn-open-more-settings"

# Run multiple scenarios
arbigent run --scenario-ids="scenario-1,scenario-2"

# Run all scenarios
arbigent run

# Run scenarios by tag
arbigent run --tags="ESPN"

# Dry run (shows plan without executing)
arbigent run --dry-run

# Run with debug logging
arbigent run --log-level=debug

# Parallel execution in CI (sharding)
arbigent run --shard=1/4   # Machine 1 runs first quarter
arbigent run --shard=2/4   # Machine 2 runs second quarter
```

## How This Differs from Maestro

| | Maestro | Arbigent |
|---|---|---|
| **Test definition** | Step-by-step script with selectors | Plain English goal |
| **When UI changes** | Tests break | AI adapts automatically |
| **Authoring** | Need to know exact element IDs | Just describe what you want |
| **Cost** | Free | Requires OpenAI API calls (~$0.01-0.05 per scenario) |
| **Speed** | Fast | Slower (AI inference per step) |
| **Best for** | Stable, repetitive flows | Exploratory, adaptive testing |

> Arbigent uses Maestro under the hood for device control. The AI decides which Maestro commands to execute.

## Troubleshooting

**"No devices found"**
- Check `adb devices` shows your device
- Ensure USB debugging is enabled on the device

**"API key error"**
- Verify your key in `.arbigent/settings.local.yml`
- Check the key works: `curl https://api.openai.com/v1/models -H "Authorization: Bearer YOUR_KEY"`

**"App not found"**
- Verify package name: `adb shell pm list packages | grep espn`
- Make sure the app is installed on the device

**Scenario fails / AI gets stuck**
- Try adding more detail to the goal
- Break complex goals into smaller chained scenarios
- Set `temperature: 0.0` in settings for more deterministic behavior

## Project Structure

```
arbigent/
  .arbigent/
    settings.local.yml       # Your local config (API key, OS, etc.) - gitignored
    espn-project.yml         # Test scenarios
  arbigent-cli/              # CLI tool source
  arbigent-core/             # Core engine
  arbigent-ui/               # Desktop GUI (requires JetBrains Runtime)
  arbigent-result/           # Output after running tests
    report.html              # Visual report
    result.yml               # Results data
    screenshots/             # Step-by-step screenshots
```