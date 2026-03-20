# Architecture

## System Overview

HyperShort is a URL shortener built with a polyglot microservices architecture. The Java backend replaces the original Go services while keeping the same frontend and infrastructure stack.

---

## Phase 4 — Full Architecture (current)

```mermaid
flowchart TD
    Browser(["🌐 Browser"])

    subgraph Gateway["Nginx API Gateway :8000 (single entry point)"]
        GW["nginx\ngateway.conf"]
    end

    subgraph WriteAPI["Write API :8080"]
        WA["write-api\nSpring Boot 3"]
    end

    subgraph ReadAPI["Read API :8081"]
        RA["read-api\nSpring Boot 3"]
    end

    subgraph AnalyticsWorker["Analytics Worker"]
        AW["analytics-worker\nKafka Consumer"]
    end

    subgraph AnalyticsAPI["Analytics API :8083"]
        AA["analytics-api\nSpring Boot 3"]
    end

    subgraph Frontend["Frontend (internal :80)"]
        FE["React + Vite\nNginx SPA"]
    end

    subgraph Infra["Infrastructure"]
        Redis[(Redis 7\n:6379)]
        Kafka[(Kafka KRaft\n:9094)]
        CH[(ClickHouse 24\n:8123)]
        H2[(H2 DB\nTCP :9092)]
    end

    Browser -- "all traffic" --> GW

    GW -- "POST /api/v1/shorten" --> WA
    GW -- "GET /{shortCode}" --> RA
    GW -- "GET /api/v1/analytics/*\nGET /api/v1/history" --> AA
    GW -- "/ and SPA fallback" --> FE

    WA --> Redis
    WA --> H2
    WA -- "url.created topic" --> Kafka

    RA --> Redis
    RA --> H2
    RA -- "url.clicked topic" --> Kafka

    Kafka --> AW
    AW --> CH
    AA --> CH

    style Gateway fill:#fef3c7,stroke:#f59e0b,stroke-width:2px
    style GW fill:#fbbf24,stroke:#d97706,color:#000
    style Browser fill:#dbeafe,stroke:#3b82f6
    style Infra fill:#f0fdf4,stroke:#86efac
```

---

## Request Flow Diagrams

### Shorten a URL

```mermaid
sequenceDiagram
    participant B as Browser
    participant G as Gateway :8000
    participant W as write-api :8080
    participant R as Redis
    participant DB as H2 DB
    participant K as Kafka

    B->>G: POST /api/v1/shorten {long_url}
    G->>W: proxy → POST /api/v1/shorten
    W->>R: GET url:{longUrl}
    alt Cache hit
        R-->>W: shortCode
        W-->>G: 201 {shortUrl, shortCode}
    else Cache miss
        W->>DB: findByLongUrl()
        alt DB hit
            DB-->>W: existing UrlMapping
        else New URL
            W->>DB: INSERT UrlMapping (placeholder)
            W->>W: encode(id) → shortCode (Feistel cipher)
            W->>DB: UPDATE shortCode
            W->>K: publish url.created {shortCode, longUrl, userId}
        end
        W->>R: SET url:{shortCode} + url:{longUrl} (TTL 24h)
        W-->>G: 201 {shortUrl, shortCode}
    end
    G-->>B: 201 {shortUrl, shortCode}
```

### Redirect a Short URL

```mermaid
sequenceDiagram
    participant B as Browser
    participant G as Gateway :8000
    participant RA as read-api :8081
    participant R as Redis
    participant DB as H2 DB
    participant K as Kafka

    B->>G: GET /{shortCode}
    G->>RA: proxy → GET /{shortCode}
    RA->>R: GET url:{shortCode}
    alt Cache hit (fast path ~1ms)
        R-->>RA: longUrl
    else Cache miss
        RA->>DB: findByShortCode()
        DB-->>RA: longUrl
        RA->>R: SET url:{shortCode} (warm cache)
    end
    RA->>K: publish url.clicked {shortCode, longUrl, clickedAt}
    RA-->>G: 302 Location: longUrl
    G-->>B: 302 redirect
```

### Analytics Pipeline

```mermaid
sequenceDiagram
    participant K as Kafka
    participant AW as analytics-worker
    participant CH as ClickHouse
    participant AA as analytics-api :8083
    participant B as Browser

    K-->>AW: url.clicked event
    AW->>CH: INSERT INTO analytics.url_clicks

    K-->>AW: url.created event
    AW->>CH: INSERT INTO analytics.url_created

    B->>AA: GET /api/v1/analytics/top?page=1&limit=10
    AA->>CH: SELECT short_code, count() GROUP BY ... ORDER BY DESC
    CH-->>AA: [{shortCode, longUrl, clickCount}]
    AA-->>B: 200 [{short_code, long_url, click_count}]

    B->>AA: GET /api/v1/history (+ Bearer JWT)
    AA->>CH: SELECT * FROM url_created WHERE user_id = ?
    CH-->>AA: [{shortCode, longUrl, createdAt}]
    AA-->>B: 200 [{short_code, long_url, created_at}]
```

---

## Short Code Generation — Feistel Cipher

Short codes are derived deterministically from the auto-incremented DB row ID using a 32-bit Feistel cipher, then base62-encoded.

```
DB ID (Long)  →  Feistel(id, rounds=4)  →  base62 encode  →  shortCode (6-8 chars)
     1        →        2891336763        →    "4jTh9q"
     2        →         892341029        →    "aB3xKp"
```

**Why Feistel instead of random?**
- Deterministic — same ID always produces same code
- No collision risk — bijective mapping
- Unpredictable — sequential IDs produce non-sequential codes (no enumeration attacks)
- No extra DB column needed to store a separate random value

---

## Data Models

### H2 (write-api / read-api)

```mermaid
erDiagram
    URL_MAPPINGS {
        BIGINT id PK
        VARCHAR long_url
        VARCHAR short_code UK
        VARCHAR user_id "nullable"
        TIMESTAMP created_at
    }
```

### ClickHouse (analytics)

```mermaid
erDiagram
    URL_CLICKS {
        String short_code
        String long_url
        DateTime64 clicked_at
    }
    URL_CREATED {
        String short_code
        String long_url
        String user_id
        DateTime64 created_at
    }
```

---

## Gateway Routing Table

| Method | Path | Upstream | Auth |
|--------|------|----------|------|
| `POST` | `/api/v1/shorten` | write-api:8080 | Optional (Google JWT) |
| `GET` | `/api/v1/analytics/top` | analytics-api:8083 | None (public) |
| `GET` | `/api/v1/history` | analytics-api:8083 | Required (Google JWT) |
| `GET` | `/{shortCode}` | read-api:8081 | None |
| `GET` | `/`, `/my-links`, `/*` | frontend:80 (SPA) | None |

> **Fallback strategy**: The gateway tries read-api for all `/{path}` requests. If read-api returns 404 (unknown short code or SPA route), Nginx silently serves the React SPA — no double round-trip visible to the browser.
