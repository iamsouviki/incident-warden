#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_PID=""
FRONTEND_PID=""

cleanup() {
  trap - INT TERM EXIT
  [[ -n "${BACKEND_PID}" ]] && kill "${BACKEND_PID}" 2>/dev/null || true
  [[ -n "${FRONTEND_PID}" ]] && kill "${FRONTEND_PID}" 2>/dev/null || true
}
trap cleanup INT TERM EXIT

cd "$ROOT_DIR"

command -v java >/dev/null || { echo "Java 21 is required. Install JDK 21 and retry."; exit 1; }
command -v mvn >/dev/null || { echo "Maven is required. Install Maven and retry."; exit 1; }
command -v npm >/dev/null || { echo "Node.js/npm is required. Install Node.js 20+ and retry."; exit 1; }
command -v curl >/dev/null || { echo "curl is required for the local health check. Install curl and retry."; exit 1; }

JAVA_MAJOR="$(java -version 2>&1 | awk -F '[.\"]' '/version/ {print $2; exit}')"
if [[ "${JAVA_MAJOR:-0}" -lt 21 ]]; then
  echo "Java 21+ is required; detected Java ${JAVA_MAJOR:-unknown}."
  exit 1
fi

mkdir -p .data logs

echo "Starting backend with the local H2 profile..."
mvn -q spring-boot:run -Dspring-boot.run.profiles=local > logs/backend-local.log 2>&1 &
BACKEND_PID=$!

for _ in $(seq 1 45); do
  if curl -fsS http://localhost:8080/api/health >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

if ! curl -fsS http://localhost:8080/api/health >/dev/null 2>&1; then
  echo "Backend did not become healthy. See logs/backend-local.log"
  exit 1
fi

echo "Starting frontend at http://localhost:5173 ..."
cd "$ROOT_DIR/frontend"
if [[ ! -d node_modules ]]; then npm ci; fi
npm run dev -- --host 0.0.0.0 > "$ROOT_DIR/logs/frontend-local.log" 2>&1 &
FRONTEND_PID=$!

cat <<EOF

Local development is ready:
  UI:      http://localhost:5173
  API:     http://localhost:8080
  Health:  http://localhost:8080/api/health
  Login:   admin / michaels@1

Press Ctrl+C to stop both services.
EOF

wait "$FRONTEND_PID"
