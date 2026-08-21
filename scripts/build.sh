#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
"$ROOT/scripts/compile.sh"
OUTPUT="$ROOT/build/deal-ui-outputs/museum-$(date +%s%N)"
"$ROOT/bin/deal-ui" build "$ROOT/examples/museum/museum.deal" --output "$OUTPUT"
