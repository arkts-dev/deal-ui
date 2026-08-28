#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
"$ROOT/scripts/compile.sh"
for example in museum/gallery checkout/checkout search-mail/search-mail kanban/kanban dashboard/dashboard; do
  "$ROOT/bin/deal-ui" build "$ROOT/examples/$example.dealui"
done
