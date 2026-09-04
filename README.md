# DEAL UI Framework

This repository implements `../deal-ui-framework-design.md` over the unmodified DEAL JVM compiler at `../fs`.

`.dealui` views and closed action dispatch are generated as ordinary DEAL and compiled with `ui/core.deal`, `ui/store.deal`, `ui/reconcile.deal`, `ui/actions.deal`, `ui/effects.deal`, and application DEAL. A generated typed Java bridge exposes only concrete entrypoints and values. Java owns parsing, compiler invocation, opaque effect threads, Swing/EDT patch application, and native event ingress; portable framework policy remains DEAL.

The modern museum gallery in `examples/museum/` demonstrates generated view composition, payload events, conditions, keyed lists, committed updates, and effects. The functional apps in `examples/checkout/`, `examples/search-mail/`, `examples/kanban/`, and `examples/dashboard/` run entirely against local in-memory state and exercise shared DEAL-owned navigation/forms, asynchronous request policy, virtualization/optimism, and overlay/lifecycle policy. Pack capabilities select renderer bindings and pack token values provide real spacing.

```bash
./scripts/lint.sh
./scripts/test.sh
./scripts/build.sh
./scripts/verify-visible.sh
./bin/deal-ui dump-ir examples/museum/gallery.dealui
./bin/deal-ui run examples/museum/gallery.dealui
./bin/deal-ui build examples/museum/gallery.dealui --renderer portable
```

`DEAL_FS_ROOT` may select another compiler checkout. Generated DEAL, JVM Java, typed bridge Java, UI IR, classes, and visible evidence are written under `build/` or private command output directories.

`--renderer portable` emits a typed `UiPortableBridge` with the same DEAL-owned store, reconciliation, action slots, updates, effects, and state snapshots, but without Swing/AWT bindings. Native renderers use `componentCapabilities()` to bind checked `.dealui-pack` capabilities to their platform components. The portable target does not permit `run`; a platform host owns lifecycle and rendering.

Deal UI handlers receive borrowed-immutable state and action values. The checker rejects direct and aliased mutation or escape to mutating helpers while leaving core DEAL semantics unchanged. Versioned component packs may constrain children to a component type, and generated portable bridges expose checked action/component/pack metadata for native hosts.

Portable applications may import the framework-owned `host/storage` declaration
from `ui/host/storage.d.deal`. It exposes typed asynchronous `load`, `save`, and
`remove` functions. Calls belong in ordinary `@ui-effect` bodies and therefore
retain the existing commit/effect/completion ordering and failure mapping. The
Android runtime supplies the reusable `HostStorage` implementation over private
`SharedPreferences`; application state serialization remains application-owned
Deal code (typically `@jsonable`), never Java reflection over game fields.

`@ui-effect-policy` may be used without `@ui-effect` only for policy-only commands that never start work, such as explicit keyed cancellation; any policy that can return `operation: "start"` requires a matching effect body. `cancellationMode: "none"` allows all same-key invocations to overlap and admits every completion in physical completion order. `"replace"` suppresses prior completions without treating running work as exited; `"interrupt"` additionally requests interruption. `awaitIdle()` waits for queued actions, delayed effects, and physically running effect code across cancellation and failure. `close()` rejects new work, interrupts outstanding effects, disposes resources once, and preserves idle accounting until effect code exits.
