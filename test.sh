#!/bin/bash
# Phase 5 — End-to-End Test Suite
# Spins up Docker Compose, runs Spring integration tests + Playwright E2E tests, tears down.

set -e

GATEWAY_URL="http://localhost:8000"
WAIT_RETRIES=40
WAIT_SLEEP=3

log() { echo "[test.sh] $*"; }

# ─── Verify prerequisites ────────────────────────────────────────────────────
for cmd in docker-compose curl; do
    command -v "$cmd" >/dev/null 2>&1 || { log "ERROR: '$cmd' not found"; exit 1; }
done

# ─── Cleanup on exit ─────────────────────────────────────────────────────────
cleanup() {
    log "Tearing down Docker Compose..."
    docker-compose down --remove-orphans 2>/dev/null || true
}
trap cleanup EXIT

# ─── 1. Start all services ───────────────────────────────────────────────────
log "Starting Docker Compose services..."
docker-compose up -d --build

# ─── 2. Wait for gateway ─────────────────────────────────────────────────────
log "Waiting for gateway at $GATEWAY_URL..."
for i in $(seq 1 $WAIT_RETRIES); do
    if curl -sf "$GATEWAY_URL/" >/dev/null 2>&1; then
        log "Gateway is ready!"
        break
    fi
    if [ "$i" -eq "$WAIT_RETRIES" ]; then
        log "ERROR: Gateway did not become ready after $((WAIT_RETRIES * WAIT_SLEEP))s"
        docker-compose logs --tail=30
        exit 1
    fi
    log "  Waiting... ($i/$WAIT_RETRIES)"
    sleep "$WAIT_SLEEP"
done

# ─── 3. Quick smoke test via gateway ─────────────────────────────────────────
log "Smoke test: POST /api/v1/shorten..."
UNIQUE_URL="https://example.com/smoke-$(date +%s)-$RANDOM"
RESPONSE=$(curl -sf -X POST "$GATEWAY_URL/api/v1/shorten" \
    -H "Content-Type: application/json" \
    -d "{\"long_url\": \"$UNIQUE_URL\"}")

if echo "$RESPONSE" | grep -q "shortUrl"; then
    SHORT_URL=$(echo "$RESPONSE" | grep -o '"shortUrl":"[^"]*"' | cut -d'"' -f4)
    SHORT_CODE=$(echo "$SHORT_URL" | sed 's|.*/||')
    log "  Created short code: $SHORT_CODE"
else
    log "ERROR: Shorten did not return shortUrl. Response: $RESPONSE"
    exit 1
fi

log "Smoke test: GET /$SHORT_CODE (expect 302)..."
REDIRECT_STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$GATEWAY_URL/$SHORT_CODE")
if [ "$REDIRECT_STATUS" = "302" ]; then
    log "  Redirect returned 302 ✓"
else
    log "ERROR: Expected 302, got $REDIRECT_STATUS"
    exit 1
fi

# ─── 4. Spring integration tests ─────────────────────────────────────────────
log ""
log "Running Spring @SpringBootTest integration tests..."
./gradlew :write-api:test :read-api:test --info
log "Spring integration tests passed ✓"

# ─── 5. Playwright E2E tests ──────────────────────────────────────────────────
log ""
log "Running Playwright E2E tests..."
cd frontend
npm ci --prefer-offline 2>/dev/null || npm install
npx playwright install --with-deps chromium
npx playwright test
cd ..
log "Playwright E2E tests passed ✓"

log ""
log "=== All Phase 5 tests passed! ==="
