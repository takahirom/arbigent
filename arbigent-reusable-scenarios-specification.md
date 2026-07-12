# Reusable Scenarios Specification

Status: Design finalized, not yet implemented.

## Motivation

Today Arbigent has two reuse mechanisms, and both have structural limits:

- `dependency` (`dependencyId`): scenarios share steps only as a **common
  prefix from the root** of a dependency chain. Two scenario trees with
  different roots cannot share a step in the middle; the goal text must be
  copy-pasted.
- `fixedScenarios` (Maestro YAML snippets): reusable, but only usable as an
  `initializationMethods` entry, never in the middle or at the end of a flow.

Reusable Scenarios add a project-level library of AI-scenario parts that can
be referenced from any position in any scenario tree, with parameters —
modeled after GitHub Actions reusable workflows (`uses` / `with` / `inputs`).

Goals addressed (from the design discussion):

- **(a)** Insert shared steps at an arbitrary position in a flow, not just as
  a prefix or during initialization.
- **(c)** Keep parts out of the executable scenario tree — a library, not a
  set of runnable scenarios.
- Parameterization (`with`), so one part serves multiple callers
  (e.g. `login` with `user: paid` vs `user: free`).

Out of scope for this iteration:

- **Cross-file / cross-project reuse.** Only same-file references are
  supported. The syntax reserves room for a future file-reference form (see
  [Reserved syntax](#reserved-syntax)).
- Inlining (the inverse of "Make this reusable").
- Automatic parameter extraction in "Make this reusable".

## Data model

### Two node forms

Every scenario — whether under `scenarios:` or `reusableScenarios:` — takes
exactly one of two forms. Mixing fields across forms is a **load-time error**.

**Leaf form** (has `goal`): carries the executable content and all execution
options, exactly as scenarios do today:

- `goal`, `type` (`Scenario` | `Execution`), `initializationMethods`,
  `maxStep`, `maxRetry`, `imageAssertions`, `imageAssertionHistoryCount`,
  `deviceFormFactor`, `userPromptTemplate`, `aiOptions`, `cacheOptions`,
  `additionalActions`, `mcpOptions`.

**Call form** (has `uses` or `steps`): a pure reference. Allowed fields:

- `uses` + `with` — call a single reusable scenario. Sugar for a
  single-entry `steps`.
- `steps` — an ordered list of `{ uses, with }` entries. **References only**;
  no inline goals inside `steps`.
- Execution options (`initializationMethods`, `maxStep`, `mcpOptions`, …) are
  **not allowed** on the call form. What runs is determined entirely by the
  definition; the call site only binds parameters and tree position.
  Writing them is a load-time error.

### Placement differences

| | `scenarios:` | `reusableScenarios:` |
|---|---|---|
| Executable / appears in the tree | yes | no (reference-only library) |
| `dependency`, `tags` | allowed | **not allowed** (tree context stays at the call site) |
| Leaf form | allowed (this is today's scenario) | allowed |
| Call form (`uses` / `steps`) | allowed | allowed (composites may nest) |

Nesting composites (a reusable calling another reusable) is allowed.
**Cycles are detected at load time** and rejected.

### Relationship to `fixedScenarios`

`fixedScenarios` stays as-is: the store for deterministic Maestro YAML
snippets, referenced via the existing `initializationMethods` entry
`MaestroYaml(scenarioId)`. No migration, no deprecation.

A reusable **leaf** may carry `initializationMethods`, so a deterministic
reusable step is expressed with existing machinery — no new execution path:

```yaml
reusableScenarios:
- id: "login-via-deeplink"
  type: "Execution"            # decision interceptor answers GoalAchieved
                               # immediately — zero AI calls
  initializationMethods:
  - type: "MaestroYaml"
    scenarioId: "login-deeplink-flow"   # -> fixedScenarios, as today
  goal: "Log in via deeplink"  # not executed; report label only
```

Division of roles: `fixedScenarios` = deterministic setup snippets, attachable
to any leaf (including reusable leaves) as initialization.
`reusableScenarios` = AI scenario parts, callable anywhere.

## Parameters

### Declaration (`inputs`)

Reusable scenarios declare their parameters. Strings only; `required` and
`default` are the only attributes:

```yaml
reusableScenarios:
- id: "login"
  inputs:
    user:
      required: true
    method:
      default: "email"
  goal: "Log in with the {{inputs.user}} account using {{inputs.method}}"
```

### Variable namespaces

- `{{name}}` (bare) — **project variable** (`appSettings.variables`),
  resolved at runtime, everywhere, exactly as today. Full backward
  compatibility; bare form never changes meaning inside a reusable.
- `{{inputs.name}}` — **declared input**, valid only inside a reusable
  definition. Load-time checked against the `inputs:` declaration.

Collisions are syntactically impossible. Both namespaces are usable in the
same goal. Input substitution also applies to the `yamlText` of Maestro
flows referenced from a reusable leaf's `initializationMethods`.

### Propagation

Explicit only (as in GitHub Actions). A composite passes values down by
writing them: `with: { user: "{{inputs.user}}" }`. No implicit inheritance.

## Load-time validation

All of the following are **errors at project load**, not runtime warnings.
(Rationale: the serializer runs with `strictMode=false`, so typos are
otherwise silently ignored; and the existing fixed-scenario lookup failure
merely logs and skips at runtime — a part silently skipped is the worst
failure mode for a test tool.)

1. `uses` referencing an id that does not exist in `reusableScenarios`.
2. Cyclic composite references.
3. Mixing leaf and call fields on one node (`goal` + `uses`, execution
   options on a call form, `dependency`/`tags` on a reusable, …).
4. `with` keys not declared in the target's `inputs`.
5. A `required` input missing from `with` (after applying `default`s).
6. `{{inputs.name}}` referencing an undeclared input; `{{inputs.*}}` used
   outside a reusable definition.
7. `uses` values containing `/`, `#`, or `@` (reserved, see below).

## Execution semantics

A call-form scenario expands, at `createArbigentScenario` time, into the flat
`List<ArbigentAgentTask>` the engine already runs — the same output shape as
dependency-chain expansion. Each reusable leaf becomes one task carrying its
own options (`mcpOptions`, `maxStep`, …), so per-task configuration works
unchanged. Retry, initialization interception, and reporting all inherit the
existing per-task behavior; no engine changes.

A call-form scenario under `scenarios:` participates in the tree normally:
it may have `dependency`, may be depended upon, and may be non-leaf.

## Reporting

Expanded tasks appear individually in the scenario's result (composite
`change-language` calling `A`, `B` yields tasks for both A and B under the
one scenario). Each task is labeled with its **call breadcrumb and bound
inputs**, e.g.:

```
premium-content-with-paid-user › login (user=paid)
change-language-to-english › navigate-to-language-settings
```

Rationale: the same part can run several times per execution; goal text alone
cannot distinguish which call failed. Implementation: `ArbigentAgentTask`
carries the originating reusable id and a snapshot of resolved `with` values
into the result model; aggregation is unchanged.

## UI (full support — level "c")

Arbigent does not ship features that cannot be driven from the GUI.

### Round-trip safety (hard requirement)

The UI model must know all new fields so that open-then-save never drops
hand-written `reusableScenarios` / `steps` / `inputs` / `uses` / `with`.

### Call-site editing: the three-way type selector

The existing "Scenario / Execution" `RadioButtonRow` group in the scenario
editor (`Scenario.kt:331-353`) is extended to three options:

```
( ) Scenario        — The agent will try to achieve the goal.
( ) Execution       — Just execute the initializations and image assertions.
( ) Reusable steps  — Call reusable scenarios in order.
```

- "Scenario" / "Execution" map to the leaf form's `type` field, as today.
  "Reusable steps" switches the node to the call form. The mapping of one
  radio group onto two schema axes is absorbed at the serialization layer.
- When "Reusable steps" is selected, the Goal text area is replaced by the
  steps list, and fields that are invalid on the call form
  (`initializationMethods`, `maxStep`, image assertions, …) are hidden —
  UI visibility mirrors the load-time validation rules exactly.
- The UI never presents a separate single-`uses` shape: the call form is
  always a steps list (one entry = single call). The `uses:` sugar is only
  produced by the YAML writer when the list has exactly one entry.
- Switching away from a form that has content (e.g. Goal with a non-empty
  goal → Reusable steps) prompts the user, offering "Make this reusable"
  as the non-destructive path.

Steps list editor:

```
┌─ Steps ─────────────────────────────────┐
│ 1. [login ▾][Browse]                     │
│    with:                                 │
│      user*: [paid        ]               │  * = required
│ 2. [play-premium-content ▾][Browse]      │
│ [+ Add step]                             │
└─────────────────────────────────────────┘
```

- Each step row selects its target with the "title + Browse" pattern already
  used by the Maestro initialization row (`Scenario.kt:766-793`), opening the
  Reusable Scenarios dialog in selection mode (same mechanism as
  `FixedScenariosDialog`'s `onScenarioSelected`).
- The `with` editor is **generated from the target's `inputs` declaration**:
  one field per declared input, required inputs marked, `default` shown as
  placeholder. Free-form key/value entry is not offered — undeclared keys
  are load-time errors, so the UI does not allow creating them.

### Definition editing: Reusable Scenarios dialog

A structural sibling of `FixedScenariosDialog` (list + add/edit/delete +
selection mode), opened from the same top-level location as the existing
"Maestro YAML Scenarios" dialog. The editor pane reuses the same scenario
content editor as `scenarios:` (three-way radio, goal editor, option
sections, steps builder) with these differences:

- `dependency` and `tags` controls are absent (not allowed on reusables).
- An **inputs declaration editor**: rows of (name, required checkbox,
  default value).
- Live validation mirroring the load-time rules (unresolved references,
  cycles, undeclared `{{inputs.*}}`), following the precedent of the
  live Maestro syntax check in `FixedScenariosDialog`.

### "Make this reusable"

A mechanical refactoring on an existing scenario X, available from the
scenario editor and offered on destructive form switches (see above).
Pressing it opens a **confirmation dialog** previewing the outcome (new
reusable id/title, the fields that will move) before anything is applied:

- Create a new reusable: move `goal` + `type` + `initializationMethods` +
  all execution-tuning fields. New id derived from the title, editable in
  the dialog.
- X becomes a call node: keeps `id` / `dependency` / `tags`, body becomes
  `uses: <new-id>`. Because X's id is unchanged, scenarios depending on X
  are unaffected.
- No automatic parameterization — the user introduces `{{inputs.*}}` by
  hand afterwards (guessing wrong silently corrupts behavior).
- Operates on the in-memory model; undo before save works naturally, but
  the confirmation dialog is the primary guard since the transformation is
  not trivially reversible by hand (no "inline" inverse in this iteration).

### Scenario tree

Call nodes appear in the tree like any scenario, labeled with the called
reusable's title and bound inputs (e.g. `login (user=paid)`), with a badge
distinguishing them from leaf scenarios.

## CLI

No new commands. Reusable scenarios are consumed transparently when the
project YAML is loaded. `--scenario-ids` targets `scenarios:` entries only;
reusable ids are not runnable.

## Reserved syntax

`uses` values are plain ids in this iteration. Ids containing `/`, `#`, or
`@` are rejected at load time so that a future file/remote reference form
(e.g. `./common.yaml#login`) can be introduced without ambiguity.

## YAML vocabulary (final)

| Concept | Key | Origin |
|---|---|---|
| Parts store | `reusableScenarios` | sibling of `fixedScenarios` |
| Reference | `uses` | GitHub Actions |
| Arguments | `with` | GitHub Actions |
| Input declaration | `inputs` (`required`, `default`) | GitHub Actions |
| Composition list | `steps` | GitHub Actions |
| Input reference | `{{inputs.name}}` | GHA `inputs.` prefix on the existing `{{...}}` resolver |

## Full example

```yaml
scenarios:
# Combination tests: compose directly at the call site; no named composite
# needed, so the parts store is not polluted by one-off combinations.
- id: "premium-content-with-paid-user"
  tags:
  - name: "billing"
  steps:
  - uses: "login"
    with: { user: "paid" }
  - uses: "play-premium-content"

- id: "premium-content-with-free-user"
  steps:
  - uses: "login"
    with: { user: "free" }
  - uses: "play-premium-content"

# A call node can also sit anywhere in a dependency chain.
- id: "change-language"
  dependency: "premium-content-with-paid-user"
  uses: "change-language-to-english"

# Ordinary and reusable nodes mix freely along a chain:
# ordinary leaf -> reusable call -> ordinary leaf.
- id: "launch-app"
  goal: "Launch the app and reach the home screen"
- id: "become-paid-user"
  dependency: "launch-app"
  uses: "upgrade-to-paid"
  with: { plan: "premium" }
- id: "go-to-content-a"
  dependency: "become-paid-user"
  goal: "Navigate to content A and open its detail page"

reusableScenarios:
# Leaf: full option set available per part.
- id: "login"
  inputs:
    user: { required: true }
  goal: "Log in with the {{inputs.user}} account on {{app_name}}"
                              # {{app_name}} is a project variable (bare form)
- id: "play-premium-content"
  goal: "Open and play any premium content; verify playback or the paywall"
  maxStep: 15

# Composite: references only, nesting allowed, cycles rejected at load.
- id: "change-language-to-english"
  steps:
  - uses: "navigate-to-language-settings"
  - uses: "set-language"
    with: { lang: "English" }

- id: "navigate-to-language-settings"
  goal: "Open Settings and navigate to System > Languages"
  mcpOptions: "..."

- id: "set-language"
  inputs:
    lang: { required: true }
  goal: "Add {{inputs.lang}} to the language list and move it to the top"

- id: "upgrade-to-paid"
  inputs:
    plan: { required: true }
  goal: "Purchase the {{inputs.plan}} plan and verify the account is upgraded"

fixedScenarios:            # unchanged; still referenced from
- id: "login-deeplink-flow"  # initializationMethods of any leaf
  title: "login via deeplink"
  yamlText: |-
    appId: "com.example.app"
    ---
    - openLink: "example://login?user={{inputs.user}}"
```
