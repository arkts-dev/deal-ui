#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
DEAL=${DEAL_FS_ROOT:-"$ROOT/../deal"}
OUT="$ROOT/build/reconcile-test"
rm -rf "$OUT"; mkdir -p "$OUT/src"
cp "$ROOT/ui/core.deal" "$ROOT/ui/reconcile.deal" "$ROOT/src/test/fixtures/reconcile/checks.deal" "$OUT/src/"
printf '%s\n' '{"languageVersion":"1.6","backend":"js","moduleRoots":["src"]}' > "$OUT/deal.json"
java -Ddeal.home="$DEAL" -cp "$DEAL/build" deal.Main compile "$OUT/src/checks.deal" --backend js --output "$OUT/js"
node "$ROOT/src/test/js/reconcile.test.js" "$OUT/js/checks.js"
