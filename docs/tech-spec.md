# Technical Specification

## Project Goal

Reimplement the backend of [dilipkumar2k6/url-shortening](https://github.com/dilipkumar2k6/url-shortening) in **Java + Spring Boot**, while keeping the frontend contract intact and making the system design choices explicit in code. The current repo is no longer a simple "write row, read row" clone; it includes a dedicated key-generation service, CDC cache warming, a layered redirect pipeline, analytics offloading, and local observability.

## Technology Choices

| Layer | Technology | Why it is here |
|---|---|---|
| Backend language | Java 17 | Stable LTS baseline across all services |
| Framework | Spring Boot 3.2 | Fast service bootstrapping, Actuator, security, JPA |
| Build tool | Gradle 8 multi-module | One repo, several deployable services |
| Auth | Google OAuth2 JWT | Stateless auth for user-owned history without a user table |
| Primary DB | PostgreSQL 16 | Transactional source of truth and CDC source |
| Cache | Redis 7 + RedisBloom | Reverse lookup cache, redirect cache, Bloom filter backing, rate limit counters |
| L1 cache | Caffeine | In-process hot-key cache for the redirect path |
| Key generation | PostgreSQL-backed dual buffer by default; Snowflake optional | Generated codes without per-request DB sequence contention |
| Message bus | Kafka (KRaft) | Decouples analytics and CDC-side event publication |
| Analytics store | ClickHouse 24 | Fast append-heavy analytical queries |
| Public ingress | Nginx | Single public entry point on `:8000` |
| Optional edge controls | Envoy + `rls-service` | Standalone rate-limited read/write entry points |
| Observability | OpenTelemetry Java agent + Actuator + SigNoz | Local traces, metrics, logs, and request correlation |
| Local orchestration | Docker Compose | Full-stack local execution |

## Current Topology

### `write-api` (`:8080`)

**Responsibility**: create short URLs, enforce custom slug rules, dedupe repeat submissions, request generated codes, persist mappings, and emit create events.

**Actual flow in code**:

1. Validate `long_url` and optional `custom_slug`.
2. For generated codes, check `Redis GET url:{longUrl}` first.
3. Fall back to `findByLongUrl()` in PostgreSQL.
4. If still absent, request the next generated code from `key-gen-service`.
5. Persist `UrlMapping`.
6. Immediately `BF.ADD` the new short code into `bf:short-codes`.
7. Publish `url.created`.
8. Do **not** inline-warm Redis; that is CDC's job.

**Key classes**:

| Class | Role |
|---|---|
| `UrlShortenController` | `POST /api/v1/shorten` entry point |
| `UrlShortenServiceImpl` | Validation, dedupe, create flow, Bloom registration |
| `KeyGenClient` | Fetches the next generated code |
| `UrlCodec` | Feistel-based encode/decode |
| `BloomFilterService` | `BF.ADD`/`BF.EXISTS` wrapper over RedisBloom |

### `read-api` (`:8081`)

**Responsibility**: resolve short codes, distinguish 404 vs 410, keep redirect latency low, and publish click events without blocking the response.

**Actual flow in code**:

1. `BF.EXISTS bf:short-codes {shortCode}`. If definitely absent, return `404`.
2. Check Caffeine L1 (`shortCodeL1Cache`).
3. Check Redis L2 (`url:{shortCode}`).
4. On Redis hit, probabilistically early-refresh with XFetch.
5. On miss or refresh trigger, query PostgreSQL.
6. If mapping exists but expired, return `410 Gone`.
7. Warm Redis and L1 from the DB result.
8. Publish `url.clicked` asynchronously.
9. Return `302 Found` with `Cache-Control: public, max-age=86400, immutable`.

**Key classes**:

| Class | Role |
|---|---|
| `RedirectController` | Redirect HTTP response construction |
| `RedirectService` | Bloom -> L1 -> L2 -> DB -> async event pipeline |
| `CacheConfig` | Caffeine L1 configuration |
| `BloomFilterService` | Read-path Bloom membership checks |
| `UrlExpiredException` | Expired-code branch (`410 Gone`) |

### `key-gen-service` (`:8085`)

**Responsibility**: generate short-code IDs continuously without turning every shorten request into a fresh global counter round-trip.

**Default implementation**:

- `DualBufferKeyGenService`
- Block size: `10000`
- Prefetch threshold: `0.8`
- Counter store: PostgreSQL `key_blocks`
- Claim SQL:

```sql
UPDATE key_blocks
SET next_id = next_id + ?
WHERE id = 1
RETURNING next_id - ?
```

**Important detail**: the service returns already encoded public short codes via `UrlCodec.encode(nextId())`. `write-api` never sees a raw counter when using this path.

**Alternative**:

- `SnowflakeKeyGenService` can be enabled via `app.key-gen.strategy=SNOWFLAKE`.

### `cdc-worker`

**Responsibility**: tail PostgreSQL WAL with Debezium and keep Redis aligned with the DB.

**Actual behavior**:

- Handles `c`, `u`, `d`, and `r` events from `url_mappings`.
- Writes both:
  - `url:{shortCode} -> longUrl`
  - `url:{longUrl} -> shortCode`
- Derives Redis TTL from `expires_at` when present.
- Evicts both keys on delete or already-expired updates.
- Publishes supplemental `url.created.cdc` events on inserts.

**Why this matters**: the write path stays lean, and Redis can self-heal from DB truth even if `write-api` crashes after commit.

### `analytics-worker`

**Responsibility**: consume append-only events and persist them to ClickHouse.

**Currently consumed topics**:

| Topic | Handler | Table |
|---|---|---|
| `url.clicked` | `onUrlClicked()` | `analytics.url_clicks` |
| `url.created` | `onUrlCreated()` | `analytics.url_created` |

`url.created.cdc` is currently **not** consumed by `analytics-worker`; it is a supplementary stream for downstream consumers or future replay-safe flows.

### `analytics-api` (`:8083`)

**Responsibility**: serve analytics queries from ClickHouse instead of PostgreSQL.

**Public/secured split**:

- `GET /api/v1/analytics/top` is public
- `GET /api/v1/history` requires a Google JWT

### `rls-service` + Envoy

**Responsibility**: optional shared rate limiting for read/write traffic.

**How it works**:

- Envoy extracts `remote_address` and calls `rls-service` over gRPC.
- `rls-service` runs a Redis Lua sliding-window algorithm using sorted sets.
- Domains:
  - `write`: 100 requests / 60 seconds / IP
  - `read`: 1000 requests / 60 seconds / IP
- Fail-open if Redis or RLS is unavailable.

**Important boundary**: the default public gateway on `:8000` routes directly to `write-api` / `read-api`. Envoy + RLS are available as alternate entry points, not the default path today.

## Authentication

Google JWTs are validated against `https://www.googleapis.com/oauth2/v3/certs`.

- `POST /api/v1/shorten` allows anonymous requests.
- Authenticated requests carry `userId` into `url.created`.
- `analytics-api` uses the same JWT validation for personal history queries.

## Cache Strategy

| Layer | Key / Structure | TTL | Purpose |
|---|---|---|---|
| Bloom gate | `bf:short-codes` | n/a | Fast reject of definitely-unknown short codes |
| L1 redirect cache | Caffeine `shortCodeL1Cache` | 60s | Absorb very hot redirect traffic in-process |
| L2 redirect cache | `url:{shortCode}` | 24h or bounded by `expires_at` | Network cache for redirect resolution |
| Reverse dedupe cache | `url:{longUrl}` | 24h or bounded by `expires_at` | Return existing short code without a DB hit |

**Write-path rule**: `write-api` only performs `BF.ADD` immediately. Redis warming is intentionally delegated to `cdc-worker`.

**Read-path rule**: `read-api` can still warm Redis/L1 on DB fallback so a cold cache does not require waiting for CDC.

**Invalidation model**:

- Redis keys are evicted by CDC delete/update handling.
- L1 Caffeine is time-bounded, not actively invalidated.
- No CDN purge flow exists in the repo today.

## Kafka Topics

| Topic | Producer | Primary consumer | Payload |
|---|---|---|---|
| `url.created` | `write-api` | `analytics-worker` | `{shortCode, longUrl, userId, createdAt}` |
| `url.clicked` | `read-api` | `analytics-worker` | `{shortCode, longUrl, clickedAt}` |
| `url.created.cdc` | `cdc-worker` | none in this repo | CDC-derived insert mirror |

Serialization is JSON. Consumers currently deserialize to `Map<String, Object>` for forward compatibility.

## ClickHouse Schema

The analytics schema is intentionally minimal today:

```sql
CREATE TABLE analytics.url_clicks (
    short_code  String,
    long_url    String,
    clicked_at  DateTime64(3, 'UTC')
) ENGINE = MergeTree()
ORDER BY (short_code, clicked_at);

CREATE TABLE analytics.url_created (
    short_code  String,
    long_url    String,
    user_id     String,
    created_at  DateTime64(3, 'UTC')
) ENGINE = MergeTree()
ORDER BY (user_id, created_at);
```

There is no geo/device/referrer enrichment pipeline in the committed code today.

## Observability

Current local stack support:

- `X-Request-Id` is generated or propagated at the gateway and echoed by services.
- Request IDs are placed in MDC so logs can be correlated.
- Java services run with the OpenTelemetry Java agent in Docker Compose.
- SigNoz is included locally for traces/metrics/log inspection.
- Spring Actuator and Prometheus endpoints are exposed per service.

## Known Gaps / Deliberate Limits

- No CDN purge workflow for deleted or abuse-removed links
- No DLQ for malformed Kafka events
- No distributed geo/device enrichment in analytics
- No consumer of `url.created.cdc` in the committed repo
- Docker Compose runs single instances; horizontal scaling concerns remain architectural, not exercised locally
