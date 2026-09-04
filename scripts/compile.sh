#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
FS_ROOT=${DEAL_FS_ROOT:-/home/igelhaus/coding/deal/fs}
BUILD="$ROOT/build"
CLASSES="$BUILD/classes"

rm -rf "$CLASSES"
mkdir -p "$CLASSES"
SOURCES=("$ROOT"/src/main/java/deal/ui/*.java "$ROOT"/src/main/java/deal/ui/runtime/*.java)
javac --release 25 -Xlint:all -Werror -cp "$FS_ROOT/build" -d "$CLASSES" "${SOURCES[@]}"
