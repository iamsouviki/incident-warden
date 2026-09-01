#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

if command -v docker >/dev/null && docker compose version >/dev/null 2>&1; then
  docker compose --profile full down
fi

pkill -f 'spring-boot:run.*spring-boot.run.profiles=local' 2>/dev/null || true
pkill -f 'vite.*--host' 2>/dev/null || true

echo "Runtime services stopped. Persistent database volumes were kept."
