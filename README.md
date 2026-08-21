# DEAL UI Preview

This repository implements the opt-in DEAL UI preview described by
`../refined-deal-jvm-ui-prototype-analysis.md`. The only authoritative DEAL
language specification is `/home/igelhaus/coding/deal/fs/docs/spec-v1.2.md`, and
ordinary DEAL compilation uses `/home/igelhaus/coding/deal/fs` with its JVM
backend. No files under that compiler repository are modified.

## Commands

```bash
./scripts/test.sh
./scripts/build.sh
./scripts/verify-visible.sh
./bin/deal-ui dump-ir examples/museum/museum.deal
./bin/deal-ui run examples/museum/museum.deal
```

`DEAL_FS_ROOT` may point to another intact checkout of the same compiler.
The build produces DEAL-generated Java, generated UI Java, typed UI IR, and
compiled classes under `build/`.

## Preview surface

- One `// @ui-root` exported view per source file
- One root component
- `Card`, `Column`, `Text`, `Button`, and `When`
- Named component properties
- String and boolean literals, direct root-state fields, and boolean negation
- One empty action class and `update(State, Action): State`
- Swing rendering on the event-dispatch thread
- Full snapshot recomposition after each committed DEAL state update

The preview syntax is not part of DEAL v1.2. The compiler strips only the
preview view before invoking the authoritative DEAL compiler; state, action,
update, and entry declarations are parsed, checked, and lowered by the real
DEAL JVM backend. Generated UI code binds those generated classes and calls the
generated exported `update` method.

Swing is part of the JDK. If the installed JDK is headless-only, `run` downloads
the matching OpenJDK desktop package into `.deps/` and assembles a repository-local
runtime without changing the system installation. A graphical desktop is required
for `run`; automated tests use headless Swing component construction and real button
dispatch. `scripts/verify-visible.sh` owns the museum-specific visual scenario;
the generic CLI contains no application-specific interaction assumptions. Each CLI
invocation uses a fresh private output root and never replaces an existing output.
