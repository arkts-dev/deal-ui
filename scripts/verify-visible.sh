#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
FS_ROOT=${DEAL_FS_ROOT:-/home/igelhaus/coding/deal/fs}
"$ROOT/scripts/compile.sh"
TEST_CLASSES="$ROOT/build/visible-test-classes"
rm -rf "$TEST_CLASSES"
mkdir -p "$TEST_CLASSES"
javac --release 25 -Xlint:all -Werror -cp "$ROOT/build/classes:$FS_ROOT/build" -d "$TEST_CLASSES" "$ROOT/src/test/java/deal/ui/GalleryVisibleSmoke.java"
JAVA=$("$ROOT/scripts/java-ui.sh")
"$JAVA" -Djava.awt.headless=false -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.GalleryVisibleSmoke
