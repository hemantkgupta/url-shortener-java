#!/bin/bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="$ROOT_DIR/.local-run"
FRONTEND_DIR="$ROOT_DIR/frontend"

mkdir -p "$RUN_DIR"

kill_port() {
  local port="$1"
  local pids
  local attempts=0

  while true; do
    pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
    if [[ -z "$pids" ]]; then
      return 0
    fi

    if (( attempts == 0 )); then
      kill $pids 2>/dev/null || true
    elif (( attempts == 5 )); then
      kill -9 $pids 2>/dev/null || true
    fi

    attempts=$((attempts + 1))
    if (( attempts > 10 )); then
      echo "Failed to clear port $port" >&2
      return 1
    fi

    sleep 1
  done
}

kill_pattern() {
  local pattern="$1"
  pkill -f "$pattern" 2>/dev/null || true
}

start_service() {
  local name="$1"
  shift

  local log_file="$RUN_DIR/$name.log"
  local pid_file="$RUN_DIR/$name.pid"

  : > "$log_file"
  (
    cd "$ROOT_DIR"
    nohup "$@" >"$log_file" 2>&1 < /dev/null &
    echo $! > "$pid_file"
  )
}

start_frontend() {
  local log_file="$RUN_DIR/frontend.log"
  local pid_file="$RUN_DIR/frontend.pid"

  if [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
    (
      cd "$FRONTEND_DIR"
      npm install --legacy-peer-deps
    ) >>"$log_file" 2>&1
  fi

  : > "$log_file"
  (
    cd "$FRONTEND_DIR"
    nohup env VITE_SHORT_LINK_BASE_URL=http://localhost:8081 npm run dev -- --host 127.0.0.1 >"$log_file" 2>&1 < /dev/null &
    echo $! > "$pid_file"
  )
}

wait_for_url() {
  local name="$1"
  local url="$2"
  local attempts="${3:-40}"

  for _ in $(seq 1 "$attempts"); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done

  echo "Failed waiting for $name at $url" >&2
  local log_file="$RUN_DIR/$name.log"
  if [[ -f "$log_file" ]]; then
    echo "--- $name log ---" >&2
    tail -n 80 "$log_file" >&2 || true
  fi
  return 1
}

wait_for_text() {
  local name="$1"
  local url="$2"
  local expected="$3"
  local attempts="${4:-30}"
  local response

  for _ in $(seq 1 "$attempts"); do
    response="$(curl -fsS "$url" 2>/dev/null || true)"
    if [[ "$response" == *"$expected"* ]]; then
      printf '%s' "$response"
      return 0
    fi
    sleep 2
  done

  echo "Failed waiting for $name response to include $expected" >&2
  echo "Last response: ${response:-<empty>}" >&2
  return 1
}

wait_for_pid_alive() {
  local name="$1"
  local attempts="${2:-20}"
  local pid_file="$RUN_DIR/$name.pid"
  local pid

  for _ in $(seq 1 "$attempts"); do
    if [[ -f "$pid_file" ]]; then
      pid="$(cat "$pid_file" 2>/dev/null || true)"
      if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        return 0
      fi
    fi
    sleep 1
  done

  echo "Failed waiting for $name process to stay alive" >&2
  local log_file="$RUN_DIR/$name.log"
  if [[ -f "$log_file" ]]; then
    echo "--- $name log ---" >&2
    tail -n 80 "$log_file" >&2 || true
  fi
  return 1
}

wait_for_log_entry() {
  local name="$1"
  local pattern="$2"
  local attempts="${3:-40}"
  local log_file="$RUN_DIR/$name.log"

  for _ in $(seq 1 "$attempts"); do
    if [[ -f "$log_file" ]] && grep -q "$pattern" "$log_file"; then
      return 0
    fi
    sleep 2
  done

  echo "Failed waiting for $name log pattern: $pattern" >&2
  if [[ -f "$log_file" ]]; then
    echo "--- $name log ---" >&2
    tail -n 80 "$log_file" >&2 || true
  fi
  return 1
}

echo "Starting infrastructure containers..."
docker compose up -d postgres redis kafka clickhouse

echo "Building Spring Boot jars..."
./gradlew --no-daemon \
  :key-gen-service:bootJar \
  :write-api:bootJar \
  :read-api:bootJar \
  :analytics-api:bootJar \
  :analytics-worker:bootJar \
  :cdc-worker:bootJar

echo "Clearing stale local ports..."
for port in 5173 8080 8081 8083 8084 8085 18082; do
  kill_port "$port"
done
for pid_file in "$RUN_DIR"/*.pid; do
  [[ -f "$pid_file" ]] || continue
  pid="$(cat "$pid_file" 2>/dev/null || true)"
  if [[ -n "$pid" ]]; then
    kill "$pid" 2>/dev/null || true
  fi
done
sleep 1
kill_pattern 'cdc-worker-0.0.1-SNAPSHOT.jar'
kill_pattern 'CdcWorkerApplication'
kill_pattern 'key-gen-service-0.0.1-SNAPSHOT.jar'
kill_pattern 'write-api-0.0.1-SNAPSHOT.jar'
kill_pattern 'read-api-0.0.1-SNAPSHOT.jar'
kill_pattern 'analytics-api-0.0.1-SNAPSHOT.jar'
kill_pattern 'analytics-worker-0.0.1-SNAPSHOT.jar'
kill_pattern 'vite --host 127.0.0.1'

echo "Starting application services..."
start_service key-gen-service \
  env \
  SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/urlshortener \
  java -jar "$ROOT_DIR/key-gen-service/build/libs/key-gen-service-0.0.1-SNAPSHOT.jar"

wait_for_log_entry key-gen-service "Started KeyGenApplication"

start_service write-api \
  env \
  SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/urlshortener \
  APP_BASE_URL=http://localhost:8081 \
  APP_KEY_GEN_URL=http://localhost:8085 \
  SPRING_FLYWAY_BASELINE_ON_MIGRATE=true \
  SPRING_FLYWAY_BASELINE_VERSION=0 \
  java -jar "$ROOT_DIR/write-api/build/libs/write-api-0.0.1-SNAPSHOT.jar"

start_service read-api \
  env \
  SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/urlshortener \
  SPRING_FLYWAY_BASELINE_ON_MIGRATE=true \
  SPRING_FLYWAY_BASELINE_VERSION=0 \
  java -jar "$ROOT_DIR/read-api/build/libs/read-api-0.0.1-SNAPSHOT.jar"

start_service analytics-api \
  env \
  java -jar "$ROOT_DIR/analytics-api/build/libs/analytics-api-0.0.1-SNAPSHOT.jar"

start_service analytics-worker \
  env \
  SERVER_PORT=18082 \
  java -jar "$ROOT_DIR/analytics-worker/build/libs/analytics-worker-0.0.1-SNAPSHOT.jar"

start_service cdc-worker \
  env \
  APP_POSTGRES_HOST=localhost \
  APP_POSTGRES_PORT=15432 \
  SPRING_DATA_REDIS_HOST=localhost \
  SPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9094 \
  APP_DEBEZIUM_SLOT_NAME=cdc_worker_local \
  APP_DEBEZIUM_SLOT_DROP_ON_STOP=true \
  APP_DEBEZIUM_OFFSET_FILE="$RUN_DIR/cdc-offsets-local.dat" \
  java -jar "$ROOT_DIR/cdc-worker/build/libs/cdc-worker-0.0.1-SNAPSHOT.jar"

start_frontend

echo "Waiting for service health endpoints..."
wait_for_log_entry write-api "Started WriteApiApplication"
wait_for_log_entry read-api "Started ReadApiApplication"
wait_for_log_entry analytics-api "Started AnalyticsApiApplication"
wait_for_log_entry analytics-worker "Started AnalyticsWorkerApplication"
wait_for_log_entry cdc-worker "Starting streaming"
wait_for_log_entry frontend "Local:"
wait_for_url write-api "http://localhost:8080/actuator/health"
wait_for_url read-api "http://localhost:8081/actuator/health"
wait_for_url analytics-api "http://localhost:8083/actuator/health"
wait_for_url analytics-worker "http://localhost:18082/actuator/health"
wait_for_pid_alive cdc-worker
wait_for_url frontend "http://localhost:5173"

echo "Running smoke test..."
SMOKE_URL="https://example.com/keygen-smoke-$(date +%s)"
SMOKE_RESPONSE="$(curl -fsS -X POST http://localhost:8080/api/v1/shorten \
  -H 'Content-Type: application/json' \
  -d "{\"long_url\":\"$SMOKE_URL\"}")"
SMOKE_CODE="$(printf '%s' "$SMOKE_RESPONSE" | sed -n 's/.*"short_code":"\([^"]*\)".*/\1/p')"
if [[ -z "$SMOKE_CODE" ]]; then
  echo "Smoke test failed: could not extract short_code from response" >&2
  echo "$SMOKE_RESPONSE" >&2
  exit 1
fi

SMOKE_REDIRECT="$(curl -sSI "http://localhost:8081/$SMOKE_CODE")"
ANALYTICS_RESPONSE="$(wait_for_text analytics-api 'http://localhost:8083/api/v1/analytics/top?page=1&limit=10' "$SMOKE_CODE" 30)"
FRONTEND_ANALYTICS_RESPONSE="$(wait_for_text frontend 'http://localhost:5173/api/v1/analytics/top?page=1&limit=10' "$SMOKE_CODE" 30)"

if [[ "$SMOKE_REDIRECT" != *"Location: $SMOKE_URL"* ]]; then
  echo "Smoke test failed: redirect did not point to $SMOKE_URL" >&2
  echo "$SMOKE_REDIRECT" >&2
  exit 1
fi

if [[ "$ANALYTICS_RESPONSE" != *"$SMOKE_CODE"* ]]; then
  echo "Smoke test failed: analytics API response did not include $SMOKE_CODE" >&2
  echo "$ANALYTICS_RESPONSE" >&2
  exit 1
fi

if [[ "$FRONTEND_ANALYTICS_RESPONSE" != *"$SMOKE_CODE"* ]]; then
  echo "Smoke test failed: frontend analytics proxy response did not include $SMOKE_CODE" >&2
  echo "$FRONTEND_ANALYTICS_RESPONSE" >&2
  exit 1
fi

printf '%s\n' "$SMOKE_RESPONSE" > "$RUN_DIR/smoke-shorten.json"
printf '%s\n' "$SMOKE_REDIRECT" > "$RUN_DIR/smoke-redirect.txt"
printf '%s\n' "$ANALYTICS_RESPONSE" > "$RUN_DIR/smoke-analytics.json"
printf '%s\n' "$FRONTEND_ANALYTICS_RESPONSE" > "$RUN_DIR/smoke-frontend-analytics.json"

cat <<EOF
Local stack is up.
- frontend: http://127.0.0.1:5173
- key-gen-service: http://127.0.0.1:8085
- write-api: http://127.0.0.1:8080
- read-api: http://127.0.0.1:8081
- analytics-api: http://127.0.0.1:8083
- analytics-worker actuator: http://127.0.0.1:18082/actuator/health
- cdc-worker log: $RUN_DIR/cdc-worker.log
- smoke short code: $SMOKE_CODE
- smoke target: $SMOKE_URL

Logs are under $RUN_DIR
EOF
