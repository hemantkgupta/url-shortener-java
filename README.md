# HyperShort — URL Shortener (Java Backend)

HyperShort is a production-style URL shortener built as a Java reimplementation of [dilipkumar2k6/url-shortening](https://github.com/dilipkumar2k6/url-shortening). The current codebase is centered on **Java 17 + Spring Boot 3**, **PostgreSQL 16**, **Redis 7**, **Kafka**, and **ClickHouse**, with explicit separation between the write path, redirect path, CDC cache maintenance, and analytics.

## Quick Start

```bash
git clone https://github.com/hemantkgupta/url-shortener-java.git
cd url-shortener-java
docker compose up --build
```

Open **http://localhost:8000**.

## Architecture

```text
Browser -> Nginx gateway :8000
  |-- POST /api/v1/shorten      -> write-api :8080
  |     |-- Redis reverse lookup (url:{longUrl})
  |     |-- PostgreSQL dedupe / insert
  |     |-- key-gen-service for generated codes
  |     `-- Kafka topic: url.created
  |
  |-- GET /{shortCode}          -> read-api :8081
  |     |-- Bloom -> Caffeine L1 -> Redis L2 (+ XFetch) -> PostgreSQL
  |     `-- Kafka topic: url.clicked
  |
  |-- GET /api/v1/analytics/*   -> analytics-api :8083
  `-- GET /api/v1/history       -> analytics-api :8083

PostgreSQL WAL -> cdc-worker -> Redis forward+reverse warming + topic: url.created.cdc
Kafka -> analytics-worker -> ClickHouse
```

See [`docs/architecture.md`](docs/architecture.md) for the detailed request flows and [`docs/url-shortener-code-companion.md`](docs/url-shortener-code-companion.md) for the repo-to-wiki sync contract.

## Services

| Service | Port | Description |
|---|---:|---|
| `gateway` | 8000 | Nginx reverse proxy and public entry point |
| `write-api` | 8080 | URL creation, custom slug handling, Feistel-based code encoding |
| `read-api` | 8081 | Redirect resolution, Bloom/L1/L2 cache pipeline, 404 vs 410 handling |
| `key-gen-service` | 8085 | Dual-buffer block allocator backed by PostgreSQL; Snowflake alternative also exists |
| `cdc-worker` | — | Debezium-based PostgreSQL WAL tailer for Redis warming and supplemental events |
| `analytics-worker` | — | Kafka consumer that writes append-only events to ClickHouse |
| `analytics-api` | 8083 | Top-links and user-history queries from ClickHouse |
| `rls-service` | 9090 gRPC / 8091 HTTP | Redis-backed sliding-window rate limit service for Envoy |
| `envoy-write` | 11000 | Optional write-path proxy wired to `rls-service` |
| `envoy-read` | 10001 | Optional read-path proxy wired to `rls-service` |
| `frontend` | internal | React 19 + Vite + Tailwind SPA |
| `postgres` | 15432 host / 5432 container | Primary transactional store |
| `redis` | 6379 | L2 cache, reverse dedupe cache, Bloom filter backing, rate-limit counters |
| `kafka` | 9094 host / 9092 container | Event backbone for created and clicked events |
| `clickhouse` | 8123 / 9000 | Analytics store |
| `signoz` | 3301 | Local tracing/metrics/log UI for the OTEL-enabled stack |

## Documentation

| Doc | Purpose |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | Request flows, system topology, and data models |
| [`docs/tech-spec.md`](docs/tech-spec.md) | Current behavior, service responsibilities, cache strategy, Kafka topics, and known gaps |
| [`docs/url-shortener-code-companion.md`](docs/url-shortener-code-companion.md) | Repo-to-`CSE-Raw` sync contract for wiki/blog maintenance |
| [`docs/api-reference.md`](docs/api-reference.md) | REST endpoints and request/response examples |
| [`docs/local-dev.md`](docs/local-dev.md) | Docker Compose and native development instructions |
| [`docs/phases.md`](docs/phases.md) | Historical build-out notes and roadmap |

## Current Capabilities

- Generated short codes come from `key-gen-service` using a PostgreSQL-backed dual-buffer allocator by default. `SNOWFLAKE` remains available as a switchable strategy.
- Public short codes are Feistel-obfuscated before Base62 encoding. The code path is deterministic and reversible for debugging.
- The redirect path is a real multi-stage read pipeline: Bloom filter -> Caffeine L1 -> Redis L2 -> PostgreSQL fallback -> async Kafka click event.
- `read-api` returns `302 Found` with `Cache-Control: public, max-age=86400, immutable` so redirects can be edge/browser cached without turning into permanent 301 behavior.
- `cdc-worker` tails PostgreSQL WAL and keeps both `url:{shortCode}` and `url:{longUrl}` synchronized in Redis. The write path does not inline-warm Redis.
- Expired links resolve as `410 Gone`, not `404`, while never-seen codes remain `404`.
- Analytics is intentionally minimal and code-first today: `url.created` and `url.clicked` feed two MergeTree tables in ClickHouse.
- Request correlation IDs and OpenTelemetry Java agents are wired into the local Docker Compose stack; SigNoz is included for local inspection.
- Envoy and `rls-service` are available for rate-limited deployments, but the default public path remains the Nginx gateway on `:8000`.

## Build

```bash
# Build all modules (skip tests)
./gradlew build -x test

# Build a specific module
./gradlew :write-api:build -x test
```
