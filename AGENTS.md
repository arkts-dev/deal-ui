# Deal UI

## Authority and boundary

`docs/deal-ui-framework-design.md` defines Deal UI; `../deal/docs/spec-v1.5.md` defines DEAL. Use v1.5 only: TypeScript syntax subset plus checked semantics, not unrestricted TypeScript. Reuse existing @ui-* mechanisms rather than duplicating them.

Own the platform-neutral transpiler and semantic editing frontend. Never depend on Android/Compose, Studio, streaming-compiler, models, prompts or concrete app scenarios. Renderers consume checked output, not reimplemented compiler rules. Deal UI exposes deterministic inspect/edit/compile/diagnostic facts; clients own generation and repair rounds.

## Canonical editing

.dealui is authoritative; rebuild ephemeral checked graphs from source, AppInterface and pack bytes per request. IDs are compiler-owned, opaque and revision-scoped; comments/labels are not identities. Edits carry exact digests and validate atomically, preserving source on failure. Preserve sealed valid slots, dependency DAGs and strongly connected repair groups. Framework validation runs inside the shared DEAL transaction, never client-side broad repair.

Query before addView/removal. Use semantic operations, not client text insertion. Consume versioned compiler AppInterface snapshots. Generalize only over syntax, typed facts, packs, capabilities and resource bounds; no app-specific validators/repairs or diagnostic-text routing.

## Application ownership

Applications must be expressible in .deal logic and .dealui views without app-specific JS/CSS/Java/Kotlin/Canvas/generated-output edits. Native code implements reusable runtime/renderers/components/capabilities. Generated output remains an implementation detail.

Keep views declarative: no array literals/indexing, mutation, arbitrary calls, renderer objects or imperative scripting. ForEach(source, item: Type, key: item.id) uses exact T[], explicit item type and unique numeric/string keys. DEAL prepares filtering/sorting/visibility/procedural collections. Before expanding the grammar, demonstrate the missing case and measure keyed reconciliation.

Check borrowed state/action immutability, typed children, capabilities and accessibility. Assertions preserve aliases; analysis must traverse their operands and infer unannotated DEAL for-of element types. Distinguish fresh outer allocations from borrowed descendants and helper-return projections. Core DEAL mutation remains valid; immutable handler boundaries do not ban fresh local writes.

## Effects and platform events

Network/storage/loading belong in @ui-effect. Animation/particles/tweening/glow/shake/fades belong in typed pack props; completion dispatches ordinary actions when relevant. Reusable components supply frame clocks, input, lifecycle and sensors through native event ingress. DEAL owns authoritative physics/gameplay unless explicitly assigned to a reusable engine; renderer interpolation never changes it.

Games keep collision, player/enemy state, death/rewards/progression/generation/transitions in DEAL; scene/HUD/menu bindings in .dealui; sprites/maps/cameras/animation/audio/input in reusable packs. Measure frame pacing, latency, keyed identity, pause/resume and disposal. Prefer measured batched TileMap/scene data over a component per tile. Fix generic bottlenecks rather than moving app policy native.

Swing capabilities are reference-renderer contracts, not Android permission to introduce app widgets. Android support needs reusable renderer abstraction, packs, lifecycle, resources and bindings. Preserve .deal rules, .dealui composition, pack contracts/tokens and native mechanics as separate layers. Styling uses typed tokens/props for colors, typography, spacing, layout, states and animation, never required app CSS/native styling.

## Session and compiler integration

runtime-js/session.js owns mounted-root drain/effect coordination; ui/session.deal owns observation/accounting and replacement readiness through compiler-issued exports. Hosts provide loading, Promise scheduling, transport and publication—not lifecycle policy. Reuse generated startDrain/store/queue/disposal/completion decisions. Private compiler-issued captures distinguish row instances and reject stale actions; never reconstruct values from labels.

Count physical effect exits even when completion is discarded. Optional checkpoints stay absent until replacement readiness; strict export rejects pending work. Never persist pending effects as resumable drafts. Keep JS runtime independent of Android/packs. Reconstruct serialized state through declaration-owned runtime plans into fresh checked arrays/classes before unknown ingress; never retag existing invariant arrays.

Use published module exports and runtime carriers, not generated class/static ABI guesses. Each independent JVM app owns runtime/JSON classes and module registry. Split generated view functions at typed component boundaries to respect JVM method limits without changing structural/key identity or authoring guidance.

ui/values.deal owns generic formatting/bounds, exposed identically by staging and sessions. It must not infer timezone, suitability, prices or provider state. Validate finite/integral inputs and use floor/ceil, never removed integer division/remainder. Test signed edges, fractions, clock ranges and fractional keys on actual JVM/JS. Construct every required field; pack defaults remain declarative metadata. Emit checked Record reads, Promise signatures, Object.hasOwn presence and undefined optionals, not obsolete syntax.

## Verification

Before adding a feature, show why updates/effects/policies/events/When/ForEach/tokens/capabilities cannot express it. Add relevant syntax, typing, borrow, lifecycle, cancellation, reconciliation, renderer parity and ingress regressions. Compilation is not execution, device behavior or visual acceptance.
