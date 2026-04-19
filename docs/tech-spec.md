# Technical Specification

## Project Goal

Re-implement the backend of [dilipkumar2k6/url-shortening](https://github.com/dilipkumar2k6/url-shortening) in **Java + Spring Boot**, keeping the original React frontend and Kubernetes manifests unchanged. The Java backend must be API-compatible with the original Go services.

---

## Technology Choices

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Backend language | Java 17 | LTS, strong ecosystem |
| Framework | Spring Boot 3.2 | Auto-configuration, actuator, security, JPA |
| Build tool | Gradle 8 (multi-module) | Faster than Maven, single `./gradlew build` for all modules |
| Auth | Google OAuth2 JWT | Stateless, no session store needed; Spring Security validates JWTs against Google's JWKS endpoint |
| Primary DB | PostgreSQL 16 | Persistent URL storage + logical replication for CDC |
| Cache | Redis 7 | Cache-aside pattern; TTL 24h; avoids DB hit on every redirect |
| Message bus | Kafka (KRaft, no ZooKeeper) | Decouples write/read path from analytics; click events are fire-and-forget |
| Analytics store | ClickHouse 24 | Columnar, optimized for `GROUP BY + COUNT` queries on append-only click data |
| API Gateway | Nginx (stable-alpine) | Lightweight reverse proxy; single port `8000` for all traffic |
| Frontend | React 19 + Vite + Tailwind | Unchanged from original repo |
| Container | Docker Compose (local) | Full stack in one command |

---

## Service Breakdown

### write-api (port 8080)

**Responsibility**: Accept URL shortening requests, persist to DB, cache in Redis, publish events to Kafka.

**Key classes**:
| Class | Purpose |
|-------|---------|
| `UrlShortenController` | `POST /api/v1/shorten` — extracts userId from JWT |
| `UrlShortenServiceImpl` | Redis check → DB check → request new code from key-gen-service |
| `KeyGenClient` | Fetches pre-allocated ID blocks from key-gen-service |
| `SecurityConfig` | Permits anonymous shorten; validates Google JWT if present |
| `UrlCreatedEvent` | Kafka payload: `{shortCode, longUrl, userId, createdAt}` |

**Short URL format**: `http://localhost:8000/{shortCode}` (gateway URL)

---

### read-api (port 8081)

**Responsibility**: Resolve short codes to long URLs, issue 302 redirects, publish click events to Kafka.

**Key classes**:
| Class | Purpose |
|-------|---------|
| `RedirectController` | `GET /{shortCode}` — returns `302 Location: {longUrl}` |
| `RedirectService` | Redis cache-aside → DB fallback; fail-open Kafka publish |
| `UrlClickedEvent` | Kafka payload: `{shortCode, longUrl, clickedAt}` |

**Cache strategy**:
```
1. GET url:{shortCode} from Redis → hit → publish click, return 302
2. Miss → SELECT from PostgreSQL → warm Redis → publish click, return 302
3. Not found → 404 (gateway falls back to SPA)
```

---

### cdc-worker (no HTTP port)

**Responsibility**: Tails PostgreSQL WAL logs via Debezium and streams changes to Redis and Kafka. Offloads cache warming from the write-api.

---

### key-gen-service (port 8085)

**Responsibility**: Generates unique, non-colliding short codes continuously using a `DUAL_BUFFER` sequence strategy backed by PostgreSQL, dispensing them to `write-api` ahead of time to ensure low latency.

---

### analytics-worker (no HTTP port)

**Responsibility**: Kafka consumer that persists click and created events to ClickHouse.

**Topics consumed**:
| Topic | Handler | ClickHouse table |
|-------|---------|-----------------|
| `url.clicked` | `onUrlClicked()` | `analytics.url_clicks` |
| `url.created` | `onUrlCreated()` | `analytics.url_created` |

**Schema bootstrap**: On startup, `ClickHouseConfig` runs `CREATE TABLE IF NOT EXISTS` — idempotent, safe to restart.

**Error handling**: Bad/malformed events are logged and skipped (no DLQ in Phase 3 — planned for Phase 6).

---

### analytics-api (port 8083)

**Responsibility**: Serve pre-aggregated analytics queries from ClickHouse to the frontend.

**Key classes**:
| Class | Purpose |
|-------|---------|
| `AnalyticsController` | REST endpoints `/api/v1/analytics/top` and `/api/v1/history` |
| `AnalyticsQueryService` | Raw JDBC queries against ClickHouse |
| `SecurityConfig` | Top analytics = public; history = requires Google JWT |

---

### gateway / Nginx (port 8000)

**Responsibility**: Single entry point. Routes by path prefix. SPA fallback via `proxy_intercept_errors`.

---

## Key Generation Service

Short codes are pre-generated and allocated in blocks using a **Dual-Buffer Sequence Strategy** backed by PostgreSQL. 

```
Buffer A (Active)     Buffer B (Standby)
[1000 - 1999]         [2000 - 2999]
```

When `write-api` needs a short code, it calls `key-gen-service`. The service dispenses codes from the active buffer in memory. When the active buffer is nearly exhausted, the standby buffer takes over while a new standby block is fetched transactionally from PostgreSQL (`key_blocks` table). This entirely side-steps database locking on every request.

**Properties:**
- Ultra-low latency ID generation
- Highly available locally
- Eliminates collision lookup penalties typical of random short code logic


---

## Authentication

Google OAuth2 JWT flow (no Firebase, no session):

```
1. Frontend: user clicks "Sign In with Google"
2. Google OAuth consent screen
3. Google returns ID token (JWT signed by Google's private key)
4. Frontend: stores token in memory (tokenStore.js)
5. Frontend: sends token as Authorization: Bearer {token} on API calls
6. write-api / analytics-api: Spring Security fetches Google's public
   keys from https://www.googleapis.com/oauth2/v3/certs and validates
   the JWT signature, expiry, and issuer automatically
7. Controller receives @AuthenticationPrincipal Jwt with sub = Google user ID
```

**Anonymous access**: `POST /api/v1/shorten` is permitted without a token. `userId` is stored as `null` in that case.

---

## Caching Strategy

| Operation | Cache key | TTL | Eviction |
|-----------|-----------|-----|---------|
| Shorten (longUrl → shortCode) | `url:{longUrl}` | 24h | LRU (default Redis) |
| Redirect (shortCode → longUrl) | `url:{shortCode}` | 24h | LRU |

Both keys are written together on every new URL creation. `cdc-worker` tails the database WAL to asynchronously warm the cache guaranteeing eventual consistency, while `read-api` explicitly warms the cache as a failover on DB fallback. No explicit invalidation (URLs are immutable once created).

---

## Kafka Topics

| Topic | Producer | Consumer | Payload |
|-------|---------|---------|---------|
| `url.created` | write-api | analytics-worker | `{shortCode, longUrl, userId, createdAt}` |
| `url.clicked` | read-api | analytics-worker | `{shortCode, longUrl, clickedAt}` |

Serialization: JSON (Spring Kafka `JsonSerializer`). No type headers — consumer uses `Map<String, Object>` for forward compatibility.

---

## ClickHouse Schema

```sql
-- Click events (from url.clicked Kafka topic)
CREATE TABLE analytics.url_clicks (
    short_code  String,
    long_url    String,
    clicked_at  DateTime64(3, 'UTC')
) ENGINE = MergeTree()
ORDER BY (short_code, clicked_at);

-- Creation events (from url.created Kafka topic)
CREATE TABLE analytics.url_created (
    short_code  String,
    long_url    String,
    user_id     String,
    created_at  DateTime64(3, 'UTC')
) ENGINE = MergeTree()
ORDER BY (user_id, created_at);
```

**Query patterns**:
```sql
-- Top URLs by click count (paginated)
SELECT short_code, long_url, count() AS click_count
FROM analytics.url_clicks
GROUP BY short_code, long_url
ORDER BY click_count DESC
LIMIT 10 OFFSET 0;

-- User's link history
SELECT short_code, long_url, created_at
FROM analytics.url_created
WHERE user_id = 'google-uid-123'
ORDER BY created_at DESC;
```

---

## Port Map

| Port | Service | Notes |
|------|---------|-------|
| **8000** | Nginx Gateway | **Primary user-facing port** |
| 8080 | write-api | Internal; exposed for debugging |
| 8081 | read-api | Internal; exposed for debugging |
| 8083 | analytics-api | Internal; exposed for debugging |
| 6379 | Redis | Internal |
| 5432 | PostgreSQL | Internal (Host port 15432) |
| 8085 | key-gen-service | Internal allocator API |
| 9094 | Kafka (external) | External listener for host-side tools |
| 8123 | ClickHouse HTTP | Internal + exposed for ClickHouse client |
| 9000 | ClickHouse TCP | Internal |

---

- No Flink stream processing (k8s manifests kept from original, not wired up)
- No SigNoz observability stack (manifests kept, not wired in Docker Compose)
- No Dead Letter Queue for failed Kafka events
- No rate limiting (Envoy k8s manifests reference this, not implemented locally)
- No horizontal scaling (single instance per service in Docker Compose)
