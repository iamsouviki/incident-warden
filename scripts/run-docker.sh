#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-quick}"

cd "$ROOT_DIR"
command -v docker >/dev/null || { echo "Docker is required. Install Docker Desktop or Docker Engine and retry."; exit 1; }
docker compose version >/dev/null 2>&1 || { echo "Docker Compose v2 is required."; exit 1; }

if [[ "$MODE" == "full" ]]; then
  echo "Starting the full stack with Keycloak, Vault, tracing, and Elastic observability..."
  docker compose --profile full up --build
else
  echo "Starting the quick stack: PostgreSQL, Redis, backend, and frontend..."
  docker compose up --build
fi
