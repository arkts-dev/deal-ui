#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
FS_ROOT=${DEAL_FS_ROOT:-"$ROOT/../deal"}
"$ROOT/scripts/compile.sh"
bash "$ROOT/scripts/test-reconcile.sh"
TEST_CLASSES="$ROOT/build/test-classes"
rm -rf "$TEST_CLASSES"
mkdir -p "$TEST_CLASSES"
TEST_SOURCES=(
  "$ROOT"/src/test/java/deal/ui/UiDiagnosticsV3Test.java
  "$ROOT"/src/test/java/deal/ui/UiFrameworkTest.java
  "$ROOT"/src/test/java/deal/ui/UiRuntimeInvariantTest.java
  "$ROOT"/src/test/java/deal/ui/UiCompilerWorkspaceTest.java
  "$ROOT"/src/test/java/deal/ui/UiCheckpointTest.java
)
javac --release 25 -Xlint:all -Werror -cp "$ROOT/build/classes:$FS_ROOT/build" -d "$TEST_CLASSES" "${TEST_SOURCES[@]}"
java -ea -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiDiagnosticsV3Test
java -ea -Djava.awt.headless=true -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiFrameworkTest
java -ea -Djava.awt.headless=true -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiRuntimeInvariantTest
java -ea -Djava.awt.headless=true -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiCompilerWorkspaceTest
# CLI resolves both compiler and framework resources independently of caller cwd.
(cd "${TMPDIR:-/tmp}" && "$ROOT/bin/deal-ui" check "$ROOT/examples/museum/gallery.dealui")
# The fixture host supplies only loading/transport; codec and lifecycle are Deal UI.
(cd "$ROOT" && java -ea -Ddeal.home="$FS_ROOT" -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiCheckpointTest "${DEAL_UI_TEST_HOST:-$ROOT/../deal-embedding/android/assets/embedding/bindings}")
