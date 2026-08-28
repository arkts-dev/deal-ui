#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
FS_ROOT=${DEAL_FS_ROOT:-/home/igelhaus/coding/deal/fs}
"$ROOT/scripts/compile.sh"
TEST_CLASSES="$ROOT/build/visible-test-classes"
rm -rf "$TEST_CLASSES"
mkdir -p "$TEST_CLASSES"
mapfile -t TEST_SOURCES < <(printf '%s\n' "$ROOT"/src/test/java/deal/ui/GalleryVisibleSmoke.java "$ROOT"/src/test/java/deal/ui/SemanticExamplesVisibleSmoke.java)
javac --release 25 -Xlint:all -Werror -cp "$ROOT/build/classes:$FS_ROOT/build" -d "$TEST_CLASSES" "${TEST_SOURCES[@]}"
JAVA=$("$ROOT/scripts/java-ui.sh")
"$JAVA" -Djava.awt.headless=false -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.GalleryVisibleSmoke
"$JAVA" -Djava.awt.headless=false -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.SemanticExamplesVisibleSmoke
