# HyperShort — URL Shortener (Java Backend)

A production-style URL shortener built as a Java re-implementation of [dilipkumar2k6/url-shortening](https://github.com/dilipkumar2k6/url-shortening), replacing the original Go microservices with **Java 17 + Spring Boot 3** while keeping the React frontend and Kubernetes manifests intact.

---

## Quick Start

```bash
git clone https://github.com/hemantkgupta/url-shortener-java.git
cd url-shortener-java
docker compose up --build
```

Open **http://localhost:8000** — that's it.

---

## Architecture

```
Browser → Nginx Gateway :8000
              ├── POST /api/v1/shorten     → write-api  :8080
              ├── GET  /{shortCode}        → read-api   :8081  → 302 redirect
              ├── GET  /api/v1/analytics/* → analytics-api :8083
              ├── GET  /api/v1/history     → analytics-api :8083
              └── GET  /                  → frontend (React SPA)

write-api / read-api  →  Redis (cache)  +  H2 DB  +  Kafka (events)
Kafka  →  analytics-worker  →  ClickHouse
analytics-api  →  ClickHouse
```

See [`docs/architecture.md`](docs/architecture.md) for full sequence diagrams and ER models.

---

## Services

| Service | Port | Description |
|---------|------|-------------|
| **gateway** | 8000 | Nginx reverse proxy — single entry point |
| write-api | 8080 | URL shortening, Feistel cipher encoding |
| read-api | 8081 | Short code resolution + 302 redirect |
| analytics-worker | — | Kafka consumer → ClickHouse writer |
| analytics-api | 8083 | Top links + user history from ClickHouse |
| frontend | — | React 19 + Vite + Tailwind (internal) |
| Redis | 6379 | Cache-aside (24h TTL) |
| Kafka | 9094 | `url.created` + `url.clicked` topics |
| ClickHouse | 8123 | Columnar analytics store |
| H2 | 9092 | In-memory DB (TCP server mode) |

---

## Documentation

| Doc | Contents |
|-----|---------|
| [`docs/architecture.md`](docs/architecture.md) | System diagram, sequence flows, data models, short code algorithm |
| [`docs/tech-spec.md`](docs/tech-spec.md) | Technology choices, service breakdown, caching strategy, Kafka topics, ClickHouse schema |
| [`docs/api-reference.md`](docs/api-reference.md) | All REST endpoints with request/response examples and curl commands |
| [`docs/local-dev.md`](docs/local-dev.md) | Docker Compose and native dev setup, env vars, how to inspect Redis/H2/ClickHouse/Kafka |
| [`docs/phases.md`](docs/phases.md) | Phase-by-phase build plan (Phases 1–7) with roadmap Gantt chart |

---

## Build

```bash
# Build all 4 Java modules (skip tests)
./gradlew build -x test

# Build a specific module
./gradlew :write-api:build -x test
```

---

## Phases Completed

- ✅ **Phase 1** — write-api + read-api + Google OAuth2 + H2 + Feistel cipher
- ✅ **Phase 2** — Redis cache-aside + Kafka event publishing
- ✅ **Phase 3** — analytics-worker (ClickHouse) + analytics-api
- ✅ **Phase 4** — Nginx API Gateway (single port, SPA fallback routing)
- 🔜 **Phase 5** — End-to-end testing (Playwright + Spring integration tests)
- 🔜 **Phase 6** — Observability (structured logs, Prometheus, Grafana)
- 🔜 **Phase 7** — PostgreSQL (replace H2 in-memory)
