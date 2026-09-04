#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
FS_ROOT=${DEAL_FS_ROOT:-/home/igelhaus/coding/deal/fs}
"$ROOT/scripts/compile.sh"
TEST_CLASSES="$ROOT/build/test-classes"
rm -rf "$TEST_CLASSES"
mkdir -p "$TEST_CLASSES"
TEST_SOURCES=(
  "$ROOT"/src/test/java/deal/ui/UiFrameworkTest.java
  "$ROOT"/src/test/java/deal/ui/UiRuntimeInvariantTest.java
  "$ROOT"/src/test/java/deal/ui/UiCompilerWorkspaceTest.java
)
javac --release 25 -Xlint:all -Werror -cp "$ROOT/build/classes:$FS_ROOT/build" -d "$TEST_CLASSES" "${TEST_SOURCES[@]}"
java -ea -Djava.awt.headless=true -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiFrameworkTest
java -ea -Djava.awt.headless=true -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiRuntimeInvariantTest
java -ea -Djava.awt.headless=true -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiCompilerWorkspaceTest
