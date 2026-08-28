# DEAL UI Framework

This repository implements `../deal-ui-framework-design.md` over the unmodified DEAL JVM compiler at `../fs`.

`.dealui` views and closed action dispatch are generated as ordinary DEAL and compiled with `ui/core.deal`, `ui/store.deal`, `ui/reconcile.deal`, `ui/actions.deal`, `ui/effects.deal`, and application DEAL. A generated typed Java bridge exposes only concrete entrypoints and values. Java owns parsing, compiler invocation, opaque effect threads, Swing/EDT patch application, and native event ingress; portable framework policy remains DEAL.

The modern museum gallery in `examples/museum/` demonstrates generated view composition, payload events, conditions, keyed lists, committed updates, and effects. Production semantic examples in `examples/checkout/`, `examples/search-mail/`, `examples/kanban/`, and `examples/dashboard/` exercise shared DEAL-owned navigation/forms, asynchronous request policy, virtualization/optimism, and overlay/lifecycle policy. Pack capabilities select Swing renderer bindings and pack token values provide real spacing.

```bash
./scripts/lint.sh
./scripts/test.sh
./scripts/build.sh
./scripts/verify-visible.sh
./bin/deal-ui dump-ir examples/museum/gallery.dealui
./bin/deal-ui run examples/museum/gallery.dealui
```

`DEAL_FS_ROOT` may select another compiler checkout. Generated DEAL, JVM Java, typed bridge Java, UI IR, classes, and visible evidence are written under `build/` or private command output directories.
