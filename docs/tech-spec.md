# Technical Specification

## Project Goal

Re-implement the backend of [dilipkumar2k6/url-shortening](https://github.com/dilipkumar2k6/url-shortening) in **Java + Spring Boot**, keeping the original React frontend and Kubernetes manifests unchanged. The Java backend must be API-compatible with the original Go services.

---

## Technology Choices

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| Backend language | Java 17 | LTS, strong ecosystem, Feistel cipher implementation |
| Framework | Spring Boot 3.2 | Auto-configuration, actuator, security, JPA |
| Build tool | Gradle 8 (multi-module) | Faster than Maven, single `./gradlew build` for all modules |
| Auth | Google OAuth2 JWT | Stateless, no session store needed; Spring Security validates JWTs against Google's JWKS endpoint |
| Primary DB | H2 (TCP server mode) | Zero-install for local dev; write-api owns the TCP server, read-api connects as a client |
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
| `UrlShortenServiceImpl` | Redis check → DB check → create new |
| `UrlCodec` | Feistel cipher encode/decode (32-bit, 4 rounds, base62) |
| `H2ServerConfig` | Starts H2 TCP server on port 9092 at app startup |
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
2. Miss → SELECT from H2 → warm Redis → publish click, return 302
3. Not found → 404 (gateway falls back to SPA)
```

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

## Feistel Cipher — Short Code Algorithm

```
Input:  DB row ID (Long, 64-bit)
Step 1: Truncate to 32-bit int (IDs are small in practice)
Step 2: Apply 4-round Feistel network with keys [k0, k1, k2, k3]
        Left  = upper 16 bits
        Right = lower 16 bits
        Each round: new_right = left XOR F(right, key)
                    new_left  = right
Step 3: Recombine → 32-bit scrambled integer
Step 4: Base62-encode (0-9, a-z, A-Z) → 6-character string

Properties:
  - Bijective (no collisions, fully reversible)
  - Sequential IDs produce non-sequential codes
  - Same ID always → same code (deterministic)
  - Codes look random (no enumeration risk)
```

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

Both keys are written together on every new URL creation. Read-api warms the cache on DB fallback. No explicit invalidation (URLs are immutable once created).

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
| 9092 | H2 TCP server | Internal (Docker network only) |
| 9094 | Kafka (external) | External listener for host-side tools |
| 8123 | ClickHouse HTTP | Internal + exposed for ClickHouse client |
| 9000 | ClickHouse TCP | Internal |

---

## Non-Goals (current phases)

- No persistent database (H2 is in-memory, resets on write-api restart)
- No Flink stream processing (k8s manifests kept from original, not wired up)
- No SigNoz observability stack (manifests kept, not wired in Docker Compose)
- No Dead Letter Queue for failed Kafka events
- No rate limiting (Envoy k8s manifests reference this, not implemented locally)
- No horizontal scaling (single instance per service in Docker Compose)
