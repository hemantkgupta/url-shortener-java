# Build Phases

This project is built incrementally. Each phase is a standalone commit that leaves the system in a fully working state.

---

## Phase 1 — Core Services ✅

**Goal**: Working URL shortener end-to-end with auth.

**What was built**:
- `write-api` (Spring Boot, port 8080)
  - `POST /api/v1/shorten` — accepts `{long_url}`, returns `{short_url, short_code}`
  - Feistel cipher for short code generation (no collisions, unpredictable)
  - H2 in-memory DB with TCP server mode (so read-api can share it)
  - Google OAuth2 JWT validation via Spring Security
  - Spring Actuator health endpoint
- `read-api` (Spring Boot, port 8081)
  - `GET /{shortCode}` — 302 redirect
  - Connects to write-api's H2 TCP server
- Frontend from original repo (React 19 + Vite + Tailwind)
- Dockerfiles for both services
- `docker-compose.yml` (write-api + read-api + frontend)

**Stack**: Java 17, Spring Boot 3.2, H2, Gradle multi-module

---

## Phase 2 — Caching + Events ✅

**Goal**: Redis cache-aside for fast redirects; Kafka for async analytics pipeline.

**What was built**:
- Redis 7 added to docker-compose
- Kafka (KRaft, no Zookeeper) added to docker-compose
- `write-api`: Redis cache check before DB; caches both `url:{shortCode}→longUrl` and `url:{longUrl}→shortCode`; publishes `url.created` to Kafka
- `read-api`: Redis cache-aside on every redirect; warms cache on DB miss; publishes `url.clicked` to Kafka (fail-open — won't block redirect)
- `UrlCreatedEvent` and `UrlClickedEvent` Kafka payload classes

**Performance improvement**: Redirects go from ~5ms (DB) to ~1ms (Redis cache hit).

---

## Phase 3 — Analytics ✅

**Goal**: Real-time click analytics stored in ClickHouse, queryable via REST.

**What was built**:
- ClickHouse 24 added to docker-compose
- `analytics-worker` (new Spring Boot module)
  - Kafka consumer for `url.clicked` → `INSERT INTO analytics.url_clicks`
  - Kafka consumer for `url.created` → `INSERT INTO analytics.url_created`
  - Schema auto-bootstrapped on startup (idempotent `CREATE TABLE IF NOT EXISTS`)
- `analytics-api` (new Spring Boot module, port 8083)
  - `GET /api/v1/analytics/top?page=1&limit=10` — public leaderboard
  - `GET /api/v1/history` — authenticated user's link history
  - Raw JDBC queries against ClickHouse
- `userId` added to `UrlMapping` entity and `UrlCreatedEvent` (nullable for anonymous)
- JWT subject extracted in controller, threaded through to entity + event

**Data flow**: click → Kafka → analytics-worker → ClickHouse → analytics-api → frontend table

---

## Phase 4 — Nginx API Gateway ✅

**Goal**: Single entry point. Eliminate multi-port confusion. Fix SPA deep links.

**What was built**:
- `nginx/gateway.conf` — routes all traffic through port 8000:
  - `POST /api/v1/shorten` → write-api
  - `GET /api/v1/analytics/*` + `GET /api/v1/history` → analytics-api
  - `GET /{shortCode}` → read-api (with `proxy_intercept_errors` fallback to SPA)
  - `GET /` → frontend
- `nginx/Dockerfile` — `nginx:stable-alpine` gateway image
- `frontend/nginx.conf` — `try_files $uri /index.html` SPA routing (was commented out)
- `frontend/Dockerfile` — enables the SPA nginx config
- `docker-compose.yml` — gateway service on port 8000; frontend no longer exposed externally
- `frontend/vite.config.js` — dev proxy mirrors gateway routing for native dev

**User-visible change**: Everything is now at `http://localhost:8000`. No more port juggling.

---

## Phase 5 — End-to-End Testing ✅

**Goal**: Playwright + Spring integration tests that run against the real gateway and verify the full stack.

**What was built**:
- Spring `@SpringBootTest` integration tests for write-api and read-api
  - `write-api`: 4 tests — 201 on valid URL, 400 on blank/missing URL, idempotent duplicate handling
  - `read-api`: 4 tests — 302 with `Location` header on known code, 404 on unknown, cache-hit path
  - Test profiles (`application-test.properties`) use embedded H2, mock Redis and Kafka via `@MockBean`
  - H2 TCP server and Google JwtDecoder replaced with mocks so tests run fully offline
- Playwright E2E tests (`frontend/tests/integration.spec.js`) targeting `http://localhost:8000`
  - Shorten URL → verify response shape and short URL contains gateway host
  - Follow redirect → verify destination URL
  - Analytics dashboard renders table headers
  - Custom slug creation and redirect verification
  - `/my-links` history page (auth gate + correct ordering)
- `test.sh` rewritten for Docker Compose:
  - Spins up full stack, waits for gateway health
  - Runs smoke test (shorten + redirect) via curl
  - Runs `./gradlew :write-api:test :read-api:test`
  - Runs `npx playwright test`
  - Tears down with `docker-compose down` on exit

---

## Phase 6 — Observability ✅

**Goal**: Structured logging, metrics, and distributed traces via SigNoz (OpenTelemetry-native).

**What was built**:
- Structured JSON logging (`logstash-logback-encoder`) in all 4 services via `logback-spring.xml`
  - Every log line is a JSON object with `service`, `level`, `message`, `timestamp`, `requestId` (MDC)
- `X-Request-Id` tracing threaded end-to-end:
  - Nginx generates the ID (`$request_id`) if client doesn't supply one
  - Forwarded via `proxy_set_header X-Request-Id` to all upstreams
  - `RequestIdFilter` (write-api, read-api, analytics-api) puts it in MDC + echoes in response header
- OpenTelemetry Java Agent (v2.3.0) baked into all 4 Docker images
  - Auto-instruments Spring Boot HTTP, JVM, JDBC, Redis, Kafka — zero code changes
  - Activated via `JAVA_TOOL_OPTIONS=-javaagent:/app/opentelemetry-javaagent.jar` in docker-compose
  - Sends traces, metrics, and logs to SigNoz via OTLP gRPC on port 4317
- SigNoz added to docker-compose (4 new services):
  - `clickhouse-signoz` — dedicated ClickHouse instance for telemetry storage
  - `otel-collector` (signoz/signoz-otel-collector) — receives OTLP, writes to ClickHouse
  - `signoz-query-service` — API backend for the SigNoz UI (port 8085)
  - `signoz` — SigNoz web UI at **http://localhost:3301**
- `signoz/otel-collector-config.yaml` — OTLP receivers → batch → ClickHouse exporter for traces/metrics/logs
- Actuator Prometheus endpoint still exposed (`/actuator/prometheus`) for ad-hoc debugging
- Micrometer service tag (`management.metrics.tags.service`) set per service

---

## Phase 7 — Persistent Storage ✅

**Goal**: Replace H2 (in-memory, resets on restart) with PostgreSQL.

**What was built**:
- PostgreSQL 16 added to docker-compose with a named volume (`postgres-data`) so data survives restarts
- `write-api` and `read-api` both connect to `postgres:5432/urlshortener` via env vars in docker-compose
- `read-api` no longer depends on `write-api` at startup (H2 TCP server gone); depends on `postgres` directly
- Flyway (`flyway-core`) added to write-api; `V1__create_url_mappings.sql` creates the table + indexes on first run
  - `short_code` UNIQUE constraint, index on `long_url` (duplicate detection), partial index on `user_id`
  - `spring.jpa.hibernate.ddl-auto=validate` in write-api — Hibernate validates against Flyway-managed schema
- HikariCP tuned in both services:
  - write-api: max 10 connections, min idle 2
  - read-api: max 20 connections, min idle 5 (heavier read load)
- `H2ServerConfig.java` deleted — TCP server no longer needed
- `h2` moved to `testRuntimeOnly` in both modules — H2 still used for `@SpringBootTest` (via `application-test.properties`)
- `spring.flyway.enabled=false` added to `write-api/application-test.properties` — schema handled by H2 `create-drop` in tests
- Test class updated: removed `@MockBean Server h2TcpServer` (class no longer exists)

---

## Roadmap Summary

```mermaid
gantt
    title URL Shortener Build Phases
    dateFormat YYYY-MM-DD
    section Core
        Phase 1 - Services + Auth     :done,    p1, 2026-03-01, 3d
        Phase 2 - Redis + Kafka       :done,    p2, after p1, 2d
        Phase 3 - Analytics           :done,    p3, after p2, 3d
        Phase 4 - Nginx Gateway       :done,    p4, after p3, 2d
    section Quality
        Phase 5 - E2E Tests           :done,    p5, after p4, 3d
        Phase 6 - Observability       :done,    p6, after p5, 3d
    section Scale
        Phase 7 - PostgreSQL          :done,    p7, after p6, 3d
```
