# DEAL UI Framework

This repository implements `../deal-ui-framework-design.md` over the unmodified DEAL JVM compiler at `../fs`.

`.dealui` views and closed action dispatch are generated as ordinary DEAL and compiled with `ui/core.deal`, `ui/store.deal`, `ui/reconcile.deal`, `ui/actions.deal`, `ui/effects.deal`, and application DEAL. A generated typed Java bridge exposes only concrete entrypoints and values. Java owns parsing, compiler invocation, opaque effect threads, Swing/EDT patch application, and native event ingress; portable framework policy remains DEAL.

The modern museum gallery in `examples/museum/` demonstrates generated view composition, payload events, conditions, keyed lists, committed updates, and effects. The functional apps in `examples/checkout/`, `examples/search-mail/`, `examples/kanban/`, and `examples/dashboard/` run entirely against local in-memory state and exercise shared DEAL-owned navigation/forms, asynchronous request policy, virtualization/optimism, and overlay/lifecycle policy. Network services and persistent storage are intentionally excluded. Pack capabilities select Swing renderer bindings and pack token values provide real spacing.

```bash
./scripts/lint.sh
./scripts/test.sh
./scripts/build.sh
./scripts/verify-visible.sh
./bin/deal-ui dump-ir examples/museum/gallery.dealui
./bin/deal-ui run examples/museum/gallery.dealui
```

`DEAL_FS_ROOT` may select another compiler checkout. Generated DEAL, JVM Java, typed bridge Java, UI IR, classes, and visible evidence are written under `build/` or private command output directories.

`@ui-effect-policy` is valid only for an action with a matching `@ui-effect`. `cancellationMode: "none"` allows all same-key invocations to overlap and admits every completion in physical completion order. `"replace"` suppresses prior completions without treating running work as exited; `"interrupt"` additionally requests interruption. `awaitIdle()` waits for queued actions, delayed effects, and physically running effect code across cancellation and failure. `close()` rejects new work, interrupts outstanding effects, disposes resources once, and preserves idle accounting until effect code exits.
