# Architecture

## System Overview

HyperShort splits the platform into four practical lanes:

- **write lane**: create or reuse a short URL
- **read lane**: resolve a short code with minimal latency
- **cdc lane**: keep Redis aligned with PostgreSQL truth
- **analytics lane**: keep dashboard queries away from the transactional store

```mermaid
flowchart TD
    Browser["Browser"]

    subgraph Public["Public Entry"]
        GW["Nginx gateway :8000"]
    end

    subgraph Write["Write Lane"]
        WA["write-api :8080"]
        KGS["key-gen-service :8085"]
    end

    subgraph Read["Read Lane"]
        RA["read-api :8081"]
        L1["Caffeine L1"]
        BF["RedisBloom gate"]
    end

    subgraph Analytics["Analytics Lane"]
        AA["analytics-api :8083"]
        AW["analytics-worker"]
    end

    subgraph CDC["CDC Lane"]
        CDCW["cdc-worker"]
    end

    subgraph Edge["Optional Edge Controls"]
        EW["envoy-write :11000"]
        ER["envoy-read :10001"]
        RLS["rls-service :9090"]
    end

    subgraph Infra["Shared Infra"]
        PG[("PostgreSQL 16")]
        Redis[("Redis 7")]
        Kafka[("Kafka")]
        CH[("ClickHouse")]
        SigNoz[("SigNoz / OTEL")]
    end

    Browser --> GW
    GW --> WA
    GW --> RA
    GW --> AA

    WA --> Redis
    WA --> PG
    WA --> KGS
    WA --> Kafka

    RA --> BF
    RA --> L1
    RA --> Redis
    RA --> PG
    RA --> Kafka

    CDCW --> PG
    CDCW --> Redis
    CDCW --> Kafka

    Kafka --> AW
    AW --> CH
    AA --> CH

    EW --> RLS
    ER --> RLS
    RLS --> Redis

    WA -. telemetry .-> SigNoz
    RA -. telemetry .-> SigNoz
    AA -. telemetry .-> SigNoz
    AW -. telemetry .-> SigNoz
    CDCW -. telemetry .-> SigNoz
```

## Request Flows

### Shorten a URL

```mermaid
sequenceDiagram
    participant B as Browser
    participant G as Gateway
    participant W as write-api
    participant R as Redis
    participant DB as PostgreSQL
    participant KGS as key-gen-service
    participant BF as Bloom filter
    participant K as Kafka
    participant CDC as cdc-worker

    B->>G: POST /api/v1/shorten
    G->>W: Forward request

    alt custom slug
        W->>DB: findByLongUrl / findByShortCode
    else generated code
        W->>R: GET url:{longUrl}
        alt reverse-key hit
            R-->>W: existing shortCode
            W-->>G: 201 existing short URL
            G-->>B: 201
        else cache miss
            W->>DB: findByLongUrl()
            alt DB hit
                DB-->>W: existing mapping
                W-->>G: 201 existing short URL
                G-->>B: 201
            else create new mapping
                W->>KGS: nextCode()
                KGS-->>W: shortCode
                W->>DB: INSERT url_mappings
                W->>BF: BF.ADD shortCode
                W->>K: publish url.created
                W-->>G: 201 new short URL
                G-->>B: 201
                DB-->>CDC: WAL change
                CDC->>R: SET url:{shortCode}, url:{longUrl}
            end
        end
    end
```

**Why this flow matters**:

- dedupe prefers Redis reverse keys first
- new short codes are created without inline Redis warming
- `BF.ADD` happens immediately so the read path does not false-404 during CDC lag

### Redirect a Short URL

```mermaid
sequenceDiagram
    participant B as Browser
    participant G as Gateway
    participant RA as read-api
    participant BF as Bloom filter
    participant L1 as Caffeine L1
    participant R as Redis
    participant DB as PostgreSQL
    participant K as Kafka

    B->>G: GET /{shortCode}
    G->>RA: Forward request
    RA->>BF: BF.EXISTS shortCode

    alt definitely absent
        BF-->>RA: false
        RA-->>G: 404
        G-->>B: 404
    else maybe present
        BF-->>RA: true
        RA->>L1: getIfPresent(shortCode)
        alt L1 hit
            L1-->>RA: longUrl
        else L1 miss
            RA->>R: GET url:{shortCode}
            alt Redis hit and no XFetch refresh
                R-->>RA: longUrl
                RA->>L1: promote
            else Redis miss or XFetch refresh
                RA->>DB: findActiveByShortCode(now)
                alt active mapping
                    DB-->>RA: longUrl
                    RA->>R: SET url:{shortCode}
                    RA->>L1: put(shortCode, longUrl)
                else expired mapping
                    RA->>DB: existsByShortCode()
                    DB-->>RA: true
                    RA-->>G: 410 Gone
                    G-->>B: 410 Gone
                else unknown code
                    DB-->>RA: none
                    RA-->>G: 404
                    G-->>B: 404
                end
            end
        end

        RA->>K: publish url.clicked asynchronously
        RA-->>G: 302 + Location + Cache-Control
        G-->>B: 302 redirect
    end
```

### CDC Cache Maintenance

```mermaid
sequenceDiagram
    participant DB as PostgreSQL
    participant CDC as cdc-worker
    participant R as Redis
    participant K as Kafka

    DB-->>CDC: WAL event on url_mappings
    alt insert or snapshot
        CDC->>R: SET url:{shortCode} -> longUrl
        CDC->>R: SET url:{longUrl} -> shortCode
        CDC->>K: publish url.created.cdc
    else update
        CDC->>R: refresh keys with ttl from expires_at
    else delete or expired
        CDC->>R: DEL url:{shortCode}
        CDC->>R: DEL url:{longUrl}
    end
```

### Analytics Flow

```mermaid
sequenceDiagram
    participant W as write-api
    participant R as read-api
    participant K as Kafka
    participant AW as analytics-worker
    participant CH as ClickHouse
    participant AA as analytics-api

    W->>K: url.created
    R->>K: url.clicked
    K->>AW: consume events
    AW->>CH: INSERT analytics.url_created / analytics.url_clicks
    AA->>CH: SELECT history / top links
```

## Data Models

### PostgreSQL

```mermaid
erDiagram
    URL_MAPPINGS {
        BIGINT id PK
        TEXT long_url
        VARCHAR short_code UK
        TEXT user_id
        TIMESTAMPTZ created_at
        TIMESTAMPTZ expires_at
    }

    KEY_BLOCKS {
        BIGINT id PK
        BIGINT next_id
    }
```

### ClickHouse

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

## Gateway Routing Table

| Method | Path | Upstream | Auth |
|---|---|---|---|
| `POST` | `/api/v1/shorten` | `write-api:8080` | Optional Google JWT |
| `GET` | `/{shortCode}` | `read-api:8081` | None |
| `GET` | `/api/v1/analytics/top` | `analytics-api:8083` | None |
| `GET` | `/api/v1/history` | `analytics-api:8083` | Required Google JWT |
| `GET` | `/` | frontend SPA | None |
