# Deal / Deal UI architecture

These rules apply to all Deal UI applications and framework work in this workspace.

## Product requirement

- Application authors must be able to implement an app using only `.deal` business logic and `.dealui` views.
- Do not solve an application feature with app-specific JavaScript, CSS, Java, Kotlin, Canvas code, Android views, or generated source maintained by the application author.
- Platform-native code is allowed only as a reusable Deal UI runtime, renderer, component, or capability that is not specific to one application or game.
- Generated JavaScript/JVM/native output is an implementation detail and must not require manual application-level edits.

## Core Deal and Deal UI authorative documents

- `./docs/deal-ui-framework-design.md` is the only authorative document for Deal UI framework. No change should violate it or create duplicate functionality.
  - In particular, all specified `@ui-*` annotations must be reused as the main framework mechanism.
- `https://github.com/arkts-dev/deal/blob/master/docs/spec-v1.2.md` is the only authorative document for the core Deal language.

## Visual effects and platform events

- Distinguish application side effects from presentation effects. Network, storage, loading, and other awaited work belong in `@ui-effect`; particles, tweening, sprite animation, glow, shake, fades, and similar presentation behavior belong in ordinary typed `.dealui-pack` components.
- Express a presentation effect through component props derived from Deal state. If completion matters to business logic, expose a typed component event such as `onComplete` that dispatches a normal Deal action.
- Implement frame timing, touch/pointer input, keyboard input, lifecycle, sensors, and similar platform event sources as reusable Deal UI components/capabilities. They dispatch typed actions through the existing native event-ingress mechanism.
- For a game, a reusable `FrameClock`-style component may emit `deltaMillis`; Deal `@ui-update` code must perform the authorative physics and gameplay transition unless the product requirement explicitly assigns physics to a reusable engine.
- Keep renderer-owned interpolation and particles presentational. They must not silently change authorative gameplay state.

## Repetition and generated content

- Use the existing `ForEach(source, item: Type, key: item.id)` construct. Do not add a competing `For` construct.
- Preserve its deliberate restrictions: the source is a path to an exact `T[]`, the item type is explicit, and the item-rooted key is a unique `int` or `string`.
- Perform filtering, sorting, visibility selection, procedural level generation, and other collection transformations in Deal. The `.dealui` view renders the prepared state.
- Keep view declarations pure and declarative. Do not add mutation, arbitrary statements, renderer object access, or general-purpose imperative scripting to `.dealui`; avoid evolving it into QML.
- Before expanding `ForEach`, document the concrete missing use case and test the current keyed reconciliation behavior and performance.

## Games

- Physics, collision response, player state, enemy behavior, death/respawn, rewards, progression, procedural generation, and level transitions belong in `.deal`.
- Scene composition, HUD, menus, overlays, and binding state to visual components belong in `.dealui`.
- Sprites, tile maps, cameras, animations, particles, audio playback, frame clocks, and input surfaces should be reusable typed components/capabilities supplied by a platform UI pack.
- Do not create one native component per tile for a large map without measuring it. Prefer a reusable batch-oriented `TileMap` or scene component while keeping level generation and gameplay decisions in Deal.
- Measure whether the Deal update/reconciliation path sustains the target frame rate. Fix framework/runtime bottlenecks or introduce generic batched scene data; do not move app-specific business logic into native code as a shortcut.

## Android renderer boundary

- The current reference JVM implementation is Swing-oriented and recognizes `renderer.swing.*` capabilities. Treat Android support as a framework gap, not as permission to add application-specific Java/Kotlin rendering.
- Android work should add a reusable renderer abstraction, Android component pack, lifecycle/event ingress, resource management, and typed bindings once for all applications.
- The desired application boundary is:

  - `game.deal`: authorative state, rules, physics, generation, progression;
  - `game.dealui`: declarative scene and UI;
  - reusable platform `.dealui-pack`: component contracts and tokens;
  - shared Android runtime/renderer implementation: platform mechanics only.

## Styling

- Prefer typed tokens and component props from Deal UI packs for colors, typography, spacing, sizing, layout, states, and animation configuration.
- Do not require application-authored CSS or native styling when the same concept should be expressible as a reusable typed Deal UI token or component property.

## Verification

- When proposing a new Deal UI feature, first show why the existing update, effect, effect-policy, component-event, `When`, `ForEach`, token, or capability mechanisms cannot express it.
- Add framework-level tests for new syntax, type checking, lifecycle, cancellation, reconciliation, renderer parity, and native event ingress as applicable.
- For game-facing renderer work, verify frame pacing, stable keyed identity, duplicate-key failure behavior, input latency, pause/resume, disposal, and absence of app-specific native code.
