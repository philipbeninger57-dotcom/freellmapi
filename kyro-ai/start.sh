#!/usr/bin/env bash
# Linux/macOS convenience launcher (Windows users: install.bat / start.bat).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT/backend"
if [ ! -x .venv/bin/python ]; then
  python3 -m venv .venv
  .venv/bin/pip install -q -r requirements.txt
fi
if [ ! -f "$ROOT/frontend/dist/index.html" ]; then
  (cd "$ROOT/frontend" && npm ci && npm run build)
fi
exec .venv/bin/python -m kyro "$@"
