#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
shellcheck "$ROOT"/bin/deal-ui "$ROOT"/scripts/*.sh
if grep -RInE '[[:blank:]]+$' "$ROOT/src" "$ROOT/examples" "$ROOT/README.md"; then
  echo "trailing whitespace found" >&2
  exit 1
fi
