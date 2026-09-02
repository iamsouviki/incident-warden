#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BACKEND_PID=""

cleanup() {
  trap - INT TERM EXIT
  [[ -n "${BACKEND_PID}" ]] && kill "${BACKEND_PID}" 2>/dev/null || true
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

echo "Building the Incident Warden JAR..."
npm run build --prefix frontend
mvn -q -DskipTests package

echo "Starting the Incident Warden JAR with the local profile..."
java -jar target/incident-warden-1.0.0.jar --spring.profiles.active=local > logs/backend-local.log 2>&1 &
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

cat <<EOF

Local development is ready:
  UI/API:  http://localhost:8080
  Health:  http://localhost:8080/api/health
  Login:   admin / admin on a fresh database — the username is the starter
           password, and the first screen is a forced password change

Press Ctrl+C to stop the JAR.
EOF

wait "$BACKEND_PID"
