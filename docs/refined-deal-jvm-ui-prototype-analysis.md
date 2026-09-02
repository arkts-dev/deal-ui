# Refined DEAL JVM UI DSL Prototype Analysis

## Revised conclusion

The updated JVM backend is substantially more capable than the earlier assessment. It is now a viable foundation for a **real compiler-integrated UI DSL prototype**, not merely a Swing host-function experiment.

However, it is still not fully conformant with `spec-v1.2.md`. The prototype must deliberately stay inside the backend’s proven subset and must not use the missing v1.2 features as architectural dependencies.

## What changed in the JVM assessment

The backend now supports, with actual `javac`/`java` tests:

- selected-entry `main(): null`
- primitive and nullable values
- nominal local and imported classes
- object-literal class construction
- class arrays and primitive arrays
- project modules
- direct function calls
- restricted first-class function values
- blocking async/await
- local tables
- four stdlib modules
- a checked reflective Java host ABI

The backend’s own scope documents much of this at:

- `fs/deal/codegen/jvm/JvmBackend.java:28-139`

Generated Java is real executable source, although the compiler only writes `.java`; tests separately invoke `javac` and `java`:

- `fs/deal/codegen/jvm/JvmBackend.java:37-45`
- `fs/deal/module/CompilationOrchestrator.java:1192-1211`

The selected entry point is already lowered to Java `main(String[])`:

- `fs/deal/codegen/jvm/JvmBackend.java:1427-1472`

So a visible Swing application can be generated and launched without inventing another backend.

# Important specification gaps

## 1. `int` is still pre-v1.2

The specification requires signed 32-bit integers:

- `fs/docs/spec-v1.2.md:89-99`
- `fs/docs/spec-v1.2.md:2183-2193`

The JVM backend currently represents DEAL `int` as Java `long` and checks the old ±\(2^{53}-1\) range:

- `fs/deal/codegen/jvm/JvmBackend.java:437-450`
- `fs/deal/codegen/jvm/JvmBackend.java:3187-3207`
- `fs/deal/codegen/jvm/JvmBackend.java:5412-5423`

This is explicitly tracked as a known failure:

- `fs/test/conformance/fixtures/jvm-v1.2-known-fail.json:5-19`

**UI consequence:** avoid exposing dimensions, coordinates, colors, or widget handles as user-facing `int` props in the initial DSL demo. Let the component pack and renderer own sizing.

## 2. Narrowing is mostly present, but not exactly as specified

The specification permits narrowing only for direct local/parameter comparisons:

```deal
x !== null
x === null
```

It excludes property-path narrowing:

- `fs/docs/spec-v1.2.md:501-531`

The frontend implements direct narrowing and invalidation:

- `fs/deal/checker/NullNarrowing.java:46-130`
- `fs/deal/checker/TypeChecker.java:783-830`

But it also accepts negated forms such as `!(x === null)`, which are not described by the spec:

- `fs/deal/checker/NullNarrowing.java:54-83`

There is also special handling for nullable foreign class member access that does not cleanly match the specified narrowing model:

- `fs/deal/checker/TypeChecker.java:1110-1129`

**UI consequence:** the first demo should use no nullable root state or nullable state paths. `When(state.expanded)` is enough.

## 3. Optional class fields are not supported by JVM lowering

Optional fields and presence semantics are normative:

- `fs/docs/spec-v1.2.md:676-685`
- `fs/docs/spec-v1.2.md:805-872`
- `fs/docs/spec-v1.2.md:953-997`

But the JVM backend rejects optional class fields, `has`, and `delete`:

- `fs/deal/codegen/jvm/JvmBackend.java:4481-4485`
- `fs/deal/codegen/jvm/JvmBackend.java:5369-5372`
- `fs/deal/codegen/jvm/JvmBackend.java:3789`

This directly affects the UI proposal because optional component props were intended to lower to optional prop-class fields:

- `ui-dsl-gap-report.md:68-91`

**Refinement:** the prototype compiler should validate named props against component-pack metadata but should not materialize those prop classes as ordinary JVM-backed DEAL classes yet. Missing optional props can remain a UI-IR concept until general optional-field lowering exists.

## 4. Class support is real but structurally restricted

The backend supports nominal classes and imported classes, but class fields cannot yet represent the full v1.2 space. It rejects several important shapes, including array fields, table fields, optional fields, and many nested class fields:

- `fs/deal/codegen/jvm/JvmBackend.java:4435-4522`
- `fs/deal/codegen/jvm/JvmBackend.java:5517-5538`

This is narrower than the normative nested-record and array-field model:

- `fs/docs/spec-v1.2.md:874-915`

**UI consequence:** use a flat root state containing only strings and booleans. Do not model the render tree as ordinary DEAL classes such as `ViewNode { children: ViewNode[] }`; generate a renderer-specific Java tree from dedicated UI IR.

## 5. Control flow remains incomplete

The JVM supports `if`, `while`, and string `for-of`, but rejects:

- C-style `for`
- array `for-of`
- `break`
- `continue`
- `try`/`catch`
- `throw`
- `delete`

Evidence:

- `fs/deal/codegen/jvm/JvmBackend.java:3784-3791`
- `fs/deal/codegen/jvm/JvmBackend.java:5125-5140`

These are normative DEAL constructs:

- `fs/docs/spec-v1.2.md:1457-1553`

**UI consequence:** this does not block a generated renderer. The initial UI grammar needs only nested components and `When`; it should defer `ForEach`.

## 6. Function values do not solve UI callbacks

The updated backend has restricted same-module function wrappers, but:

- function expressions are rejected
- closures are rejected
- complex function signatures are rejected
- cross-module function values are rejected
- host function parameters are rejected

Evidence:

- `fs/deal/codegen/jvm/JvmBackend.java:5365-5368`
- `fs/deal/codegen/jvm/JvmBackend.java:5887-5902`
- `fs/test/conformance/fixtures/jvm-function-values-slice.json:629-692`
- `fs/deal/codegen/jvm/JvmBackend.java:4085-4093`

Therefore the proposed UI must not lower:

```deal
onClick: function() { ... }
```

That is good: the proposal already rejects imperative callbacks and allows only typed action literals:

- `initial-deal-ui-dsl-design.md:259-302`
- `ui-dsl-gap-report.md:93-117`

A generated Java `ActionListener` can dispatch a statically known action ID directly to generated runtime code. No DEAL function value needs to cross a host or module boundary.

## 7. Tables and JSON remain unsuitable as the UI tree transport

The JVM has limited local tables, but rejects table writes, indexing, `std/table`, `std/json`, and table-valued host boundaries:

- `fs/deal/codegen/jvm/JvmBackend.java:102-115`
- `fs/deal/codegen/jvm/JvmBackend.java:8220-8347`
- `fs/deal/codegen/jvm/JvmBackend.java:8450-8458`

`@jsonable` is also rejected by JVM lowering:

- `fs/deal/codegen/jvm/JvmBackend.java:4449-4452`

**UI consequence:** do not serialize the view to a DEAL `table` or JSON string. Lower the typed UI AST directly to generated Java builder operations.

## 8. Runtime diagnostics are not v1.2-complete

The specification requires runtime errors with original source location, expected/actual values, stack information, and cause:

- `fs/docs/spec-v1.2.md:2041-2061`
- `fs/docs/spec-v1.2.md:2103-2119`

The JVM runtime exception currently carries primarily an error code and message:

- `fs/deal/codegen/jvm/JvmBackend.java:3179-3186`

The orchestrator explicitly emits no JVM source-map sidecars:

- `fs/deal/module/CompilationOrchestrator.java:1102-1112`

**UI consequence:** preserve UI source spans in the UI IR immediately, even if the initial runtime cannot yet report them fully. Otherwise generated listener/render failures will be very difficult to trace later.

# Refined prototype architecture

```text
Preview DEAL UI source
        ↓
existing DEAL lexer/parser
 + dedicated view/UI parser
        ↓
ordinary DEAL state/action/update checking
 + dedicated component-pack/UI checking
        ↓
typed renderer-neutral UI IR
        ↓
existing JVM backend
 + UI lowering
        ↓
generated Java module
 + small reusable Swing renderer runtime
        ↓
javac
        ↓
visible interactive Swing application
```

This is more realistic than routing the proposal through a scalar `host/swing` API.

A scalar Swing host API is useful as a runtime experiment, but it would only demonstrate calls such as `addButton(...)`; it would not validate the proposed:

- pure `view` grammar
- restricted `UiExpr`
- named props
- child policy
- action literals
- root-view contract
- snapshot recomposition

# What should remain ordinary DEAL

Use current, proven JVM features for state, actions, and update logic:

```deal
export class MuseumState {
  title: string = "The Starry Night";
  artist: string = "Vincent van Gogh";
  expanded: boolean = false;
}

export class ToggleDetails {}

export function update(
  state: MuseumState,
  action: ToggleDetails
): MuseumState {
  return {
    title: state.title,
    artist: state.artist,
    expanded: !state.expanded
  };
}
```

This stays within the strongest JVM subset:

- flat nominal classes
- primitive/string fields
- object-literal construction
- direct synchronous function call
- boolean negation
- class parameter and return values

No arrays, optional fields, tables, JSON, nullable paths, async, closures, or host objects are required.

# What should be a genuine preview language extension

```deal
// @ui-root
export view MuseumCard(state: MuseumState): View {
  Card {
    Column {
      Text(value: state.title)
      Text(value: state.artist)

      When(state.expanded) {
        Text(value: "Painted in 1889")
      }

      Button(
        text: "Toggle details",
        onClick: action ToggleDetails {}
      )
    }
  }
}
```

The current v1.2 grammar has no `view`, `View`, `Action`, named calls, or action literals:

- `fs/docs/spec-v1.2.md:153-235`
- `fs/docs/spec-v1.2.md:407-445`

Unknown directives are errors:

- `fs/docs/spec-v1.2.md:47-52`

Therefore this must be opt-in preview syntax, not silently treated as v1.2. Conceptually:

```bash
deal compile museum.deal --backend jvm --ui-preview
```

# UI compiler slice

## Dedicated UI AST

Use distinct nodes:

```text
ViewDeclaration
ComponentNode
WhenNode
NamedUiProp
StatePathExpr
LiteralUiExpr
ActionLiteral
```

Do not lower UI syntax into ordinary `CallExpr`, `ObjectLiteralExpr`, or `FunctionExpr`.

The normal expression AST includes assignment, arbitrary calls, function expressions, and await:

- `fs/deal/ast/ExpressionNode.java:6-20`

The proposal specifically requires these forms to be syntactically impossible inside views:

- `initial-deal-ui-dsl-design.md:23-59`

## Minimal component pack

Hard-code or load one preview pack:

```text
Card
  children: many

Column
  children: many

Text
  value: required string
  children: none

Button
  text: required string
  onClick: required Action
  children: none
```

The checker must reject invented components and properties as required by:

- `initial-deal-ui-dsl-design.md:159-180`
- `ui-dsl-gap-report.md:179-208`

Do not implement `Image`, `Input`, `ForEach`, tokens, formatters, or user-defined components yet.

## Restricted expressions

The first slice only needs:

```text
string literal
boolean literal
state.field
!state.booleanField
```

Although the broader proposal allows a larger `UiExpr`:

- `ui-dsl-gap-report.md:40-66`

limiting the first implementation avoids relying on unresolved narrowing, nullable paths, arbitrary formatting, table reads, or JVM expression gaps.

# JVM lowering

The UI lowering should generate Java code equivalent to:

```text
buildRoot(state)
  → UiNode/Card
      → UiNode/Column
          → UiNode/Text
          → conditional UiNode/Text
          → UiNode/Button(actionId)
```

The Swing runtime maps nodes to widgets:

| UI component | Swing widget |
|---|---|
| `Card` | `JPanel` with border/background |
| `Column` | `JPanel` with vertical `BoxLayout` |
| `Text` | `JLabel` |
| `Button` | `JButton` |
| `When(false)` | absent subtree |

The generated button listener should not call a DEAL callback value. It should invoke generated dispatch directly:

```text
Java ActionListener
→ generated dispatch(ToggleDetails)
→ generated call update(state, action)
→ commit returned state
→ rebuild/diff root
→ patch Swing tree
```

This avoids every current callback limitation:

- no function expression
- no closure
- no host callback parameter
- no cross-module function wrapper
- no event table
- no JSON envelope

It also exactly follows the proposal’s action boundary:

- `initial-deal-ui-dsl-design.md:304-353`

# Rendering strategy

The proposal’s normative semantics are snapshot recomposition:

- `initial-deal-ui-dsl-design.md:457-469`
- `ui-dsl-gap-report.md:24-38`

For the first demo:

1. evaluate the complete root from committed state
2. construct the next renderer-neutral tree
3. compare it with the previous tree
4. replace or patch the root panel
5. call `revalidate()` and `repaint()`

A complete subtree rebuild is acceptable initially because the observable result matches full recomposition. Keep the `JFrame` stable so the window does not flicker or move.

All Swing operations must execute on the Swing EDT. DEAL update logic can run synchronously for this small demo because it performs no async or host operations.

# Revised implementation order

## Phase 1: prove the current JVM baseline

Before UI work, add or run one production-path fixture containing:

- selected `main(): null`
- flat state class
- empty action class
- `update(state, action): state`
- generated Java compiled with `javac`
- generated entry executed with `java`

This verifies the exact DEAL subset the UI prototype will depend on.

## Phase 2: UI front end and typed dump

Implement:

- preview flag
- `view`
- root marker
- dedicated UI AST
- four-component pack
- named props
- state paths
- `When`
- one action literal
- typed UI dump

No Swing yet. This proves the language restrictions independently of rendering.

## Phase 3: Swing renderer/runtime

Implement:

- Java `UiNode`
- Swing component creation
- stable window
- EDT confinement
- full-root recomposition
- source-span storage in nodes

## Phase 4: generated action dispatch

Support exactly one action type and one update signature:

```text
update(RootState, ActionClass): RootState
```

The general multi-action representation remains unresolved because DEAL lacks closed unions:

- `initial-deal-ui-dsl-design.md:304-324`

## Phase 5: visible museum demo

Verify:

1. window opens
2. card has visible styling
3. title and artist render
4. click emits `ToggleDetails`
5. DEAL `update` returns changed state
6. details appear/disappear
7. repeated clicks remain stable
8. no UI-side business callback exists

# Features to defer

Do not make the first demo depend on:

- optional prop classes in ordinary JVM lowering
- nullable state fields or narrowing
- arrays in state classes
- `ForEach`
- table action envelopes
- JSON
- `@jsonable`
- function callbacks
- async effects
- `try`/`catch`
- bytes or images
- raw dimensions
- compiler-produced JAR packaging
- full source-map support

These are real gaps, but none is necessary to demonstrate the core UI proposal.

## Final refined claim

The current JVM backend is now sufficient for a materially visible, interactive prototype **if the UI compiler lowers its dedicated typed UI tree directly to generated Java and Swing**.

It is not sufficient for a general UI framework built from ordinary DEAL classes, optional prop records, callbacks, JSON trees, or host-passed component objects. The realistic first demo is therefore a narrow compiler vertical slice: flat DEAL state, one typed action, one update function, four UI components, one conditional, generated Swing widgets, and snapshot recomposition.
