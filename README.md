# DEAL UI

Implements [framework design](docs/deal-ui-framework-design.md) on DEAL v1.6 at `../deal`: a TypeScript syntax subset with checked semantics, not unrestricted TypeScript.

.dealui views and closed dispatch lower to ordinary DEAL with ui/core, store, reconcile, actions and effects. Published compiler exports/runtime carriers feed typed JVM or portable bridges. Each independent app owns its JVM registry. Java supplies parsing, compilation, effect threads, native ingress and Swing/EDT patches; portable policy stays in DEAL.

ui/values provides checked decimal, clockMinutes and inBounds without timezone/domain policy. Borrowed handler state/action and descendants are immutable, including assertion aliases; fresh locals and read-only helpers remain valid. Packs constrain props, children and events.

runtime-js/session.js coordinates mounted-root drain/effects using compiler-issued ui/session exports for in-flight accounting, faults, versions and replacement readiness. Hosts provide loading and scheduling, not application transitions. Settled checkpoints exclude pending work.

```sh
./scripts/lint.sh
./scripts/test.sh
./scripts/build.sh
./scripts/verify-visible.sh
./bin/deal-ui dump-ir examples/museum/gallery.dealui
./bin/deal-ui run examples/museum/gallery.dealui
./bin/deal-ui build examples/museum/gallery.dealui --renderer portable
```

The CLI and build scripts default to the sibling `../deal` compiler checkout, independent of the calling directory. Set DEAL_FS_ROOT to another matched compiler checkout. Outputs/evidence stay under build/ or private command directories. Examples cover gallery composition, payloads, keyed lists, forms, navigation, async policies, optimism, virtualization and overlays using local state.

## Diagnostics

Compiler diagnostics use the shared [JSON v3 protocol](../deal/fs/docs/diagnostics-protocol-v3.md): no legacy envelopes or fallback formats. `--diagnostics-json PATH` writes accepted/rejected/failed reports; source rejections without that option print the same v3 report to stderr. Exit 1 denotes rejection, exit 2 an operational failure when a report is requested.

`UiDiagnostic.toJson(projectRoot)` serializes the complete retained producer batch, shared file revisions, UTF-16 ranges, context, notes and evidence. `withRepairArtifact` and node attachment preserve that batch. UI-owned syntax/type rules carry their own registered source responsibility; core producer facts are not reconstructed from messages. Sources are captured when consumed, never reread during diagnostic serialization. Hosts must transport the full JSON unchanged rather than select `compilerDiagnostic()` or flatten exception messages.

Use `UiInspection.toJson()`, `UiChangeResult.toJson()`, `CanonicalCompiler.Inspection.toJson()`, or `UiDiagnosticJson.encode(response)` for UI diagnostic-bearing responses. Repair results use the same encoder and shared diagnostic indexes in slots. Java records remain in-process facts, not an alternative diagnostic wire format. `UiCompilationFailure.toJson()` retains the downstream compiler report verbatim.

Embedding-owned fragment and Android adapters must consume these batch/report APIs; this repository does not own those adapters. Runtime/session/checkpoint protocols are separate and unchanged.

Portable UiPortableBridge preserves store/reconciliation/action/effect/state semantics without Swing/AWT; checked metadata supplies root/action/component/pack identities and capabilities. Native hosts own lifecycle/rendering; portable targets cannot run standalone.

host/storage exposes async load/save/remove through ordinary @ui-effect ordering. Android uses private preferences; DEAL owns serialization and save/restore decisions, never native reflection.

@ui-effect-policy without an effect may cancel but never start work. none admits overlapping completions; replace suppresses earlier completions while retaining physical-exit accounting; interrupt also requests interruption. awaitIdle waits for queued, delayed and physically running work. close rejects new work, interrupts, disposes once and preserves accounting until exit.
