# Local Development Guide

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java | 17+ | `brew install openjdk@17` |
| Docker Desktop | latest | [docker.com](https://www.docker.com/products/docker-desktop/) |
| Node.js | 20+ | `brew install node` |
| Git | any | pre-installed on macOS |

---

## Option A — Full Stack with Docker Compose (recommended)

Runs every service in Docker. Single command, mirrors production.

```bash
# Clone and enter the repo
git clone https://github.com/hemantkgupta/url-shortener-java.git
cd url-shortener-java

# Build and start all services
docker compose up --build

# First run takes ~3-5 minutes (downloading images + building JARs)
# Subsequent runs: ~30 seconds
```

**Access points**:

| URL | What |
|-----|------|
| `http://localhost:8000` | App (via gateway) — use this! |
| `http://localhost:8000/api/v1/analytics/top` | Top links JSON |
| `http://localhost:8080/h2-console` | H2 DB browser (JDBC URL: `jdbc:h2:tcp://localhost:9092/mem:urlshortener`) |
| `http://localhost:8123` | ClickHouse HTTP interface |

**Tear down**:
```bash
docker compose down          # stop containers
docker compose down -v       # stop + delete volumes
```

---

## Option B — Native Dev Loop (faster iteration)

Run infra in Docker, services natively with Gradle. Hot reload possible.

### Step 1 — Start infrastructure only

```bash
docker compose up postgres redis kafka clickhouse -d
```

`postgres` is published on host port `15432` to avoid colliding with a locally installed PostgreSQL daemon.

### Step 2 — Start write-api

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/urlshortener \
./gradlew :write-api:bootRun --args='--app.base-url=http://localhost:8081'
# Starts on :8080
```

### Step 3 — Start read-api (new terminal)

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:15432/urlshortener \
./gradlew :read-api:bootRun
# Starts on :8081
```

### Step 4 — Start analytics-worker (new terminal)

```bash
./gradlew :analytics-worker:bootRun
# Connects to Kafka at localhost:9094, ClickHouse at localhost:8123
```

### Step 5 — Start cdc-worker (new terminal, optional but recommended)

```bash
POSTGRES_HOST=localhost POSTGRES_PORT=15432 ./gradlew :cdc-worker:bootRun
# Streams PostgreSQL WAL changes into Kafka and Redis
```

This service does not expose an HTTP actuator endpoint. In Docker Compose it
is health-checked by process liveness, not by curling a port.

### Step 6 — Start analytics-api (new terminal)

```bash
./gradlew :analytics-api:bootRun
# Starts on :8083
```

### Step 7 — Start frontend dev server (new terminal)

```bash
cd frontend
npm install --legacy-peer-deps
VITE_SHORT_LINK_BASE_URL=http://localhost:8081 npm run dev -- --host 0.0.0.0
# Vite starts on :5173 with proxy to backend services
```

**Access points in native mode**:

| URL | What |
|-----|------|
| `http://localhost:5173` | Frontend (Vite dev server with HMR) |
| `http://localhost:8080` | write-api direct |
| `http://localhost:8081` | read-api direct |
| `http://localhost:8083` | analytics-api direct |

> **Note**: Short code redirects in native mode go directly to read-api (`:8081/{code}`). The Nginx gateway is Docker-only.

---

## Building

```bash
# Build all modules (skip tests)
./gradlew build -x test

# Build a specific module
./gradlew :write-api:build -x test
./gradlew :analytics-api:build -x test

# Run tests (requires Redis + Kafka + ClickHouse running)
./gradlew test

# Clean all build outputs
./gradlew clean
```

---

## Environment Variables

All defaults work out of the box for local dev. Override via environment or `application.properties`:

### write-api
| Variable | Default | Description |
|----------|---------|-------------|
| `APP_BASE_URL` | `http://localhost:8000` | Base URL for generated short links |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/urlshortener` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `urluser` | PostgreSQL username |
| `SPRING_DATASOURCE_PASSWORD` | `urlpass` | PostgreSQL password |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis host |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis port |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka brokers |

### read-api
| Variable | Default | Description |
|----------|---------|-------------|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/urlshortener` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `urluser` | PostgreSQL username |
| `SPRING_DATASOURCE_PASSWORD` | `urlpass` | PostgreSQL password |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis host |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis port |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka brokers |

### analytics-worker & analytics-api
| Variable | Default | Description |
|----------|---------|-------------|
| `APP_CLICKHOUSE_URL` | `jdbc:clickhouse://localhost:8123/analytics` | ClickHouse URL |
| `APP_CLICKHOUSE_USERNAME` | `default` | ClickHouse user |
| `APP_CLICKHOUSE_PASSWORD` | `` (empty) | ClickHouse password |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka brokers (worker only) |

### cdc-worker
| Variable | Default | Description |
|----------|---------|-------------|
| `POSTGRES_HOST` | `localhost` | PostgreSQL host for Debezium |
| `POSTGRES_PORT` | `5432` | PostgreSQL port for Debezium |
| `POSTGRES_DB` | `urlshortener` | PostgreSQL database |
| `POSTGRES_USER` | `urluser` | PostgreSQL user |
| `POSTGRES_PASSWORD` | `urlpass` | PostgreSQL password |
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis host |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis port |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | Kafka brokers |

---

## Testing a Shorten + Redirect Flow

```bash
# 1. Shorten a URL
curl -s -X POST http://localhost:8000/api/v1/shorten \
  -H "Content-Type: application/json" \
  -d '{"long_url":"https://github.com/hemantkgupta/url-shortener-java"}' | jq .

# Expected output:
# {
#   "short_code": "4jTh9q",
#   "short_url":  "http://localhost:8000/4jTh9q",
#   "long_url":   "https://github.com/hemantkgupta/url-shortener-java"
# }

# 2. Follow the redirect
curl -I http://localhost:8000/4jTh9q
# HTTP/1.1 302 Found
# Location: https://github.com/hemantkgupta/url-shortener-java

# 3. Check analytics (wait ~2s for Kafka → ClickHouse)
curl -s http://localhost:8000/api/v1/analytics/top | jq .
```

---

## Useful Docker Commands

```bash
# View logs for a specific service
docker compose logs -f write-api
docker compose logs -f analytics-worker

# Restart one service without rebuilding
docker compose restart write-api

# Rebuild and restart one service
docker compose up --build write-api

# Check all service health
docker compose ps

# Open a shell in a running container
docker compose exec write-api sh
docker compose exec clickhouse clickhouse-client
docker compose exec redis redis-cli
```

---

## Inspecting Data

### Redis
```bash
docker compose exec redis redis-cli
> KEYS url:*                      # list all cached URLs
> GET url:4jTh9q                  # get longUrl for shortCode
> TTL url:4jTh9q                  # check TTL (should be ~86400s)
```

### H2 Database
Open `http://localhost:8080/h2-console` and connect with:
- JDBC URL: `jdbc:h2:tcp://localhost:9092/mem:urlshortener`
- Username: `sa`
- Password: *(empty)*

```sql
SELECT * FROM URL_MAPPINGS ORDER BY CREATED_AT DESC LIMIT 10;
```

### ClickHouse
```bash
docker compose exec clickhouse clickhouse-client

> SELECT * FROM analytics.url_clicks ORDER BY clicked_at DESC LIMIT 10;
> SELECT short_code, count() AS clicks FROM analytics.url_clicks GROUP BY short_code ORDER BY clicks DESC;
> SELECT * FROM analytics.url_created ORDER BY created_at DESC LIMIT 10;
```

### Kafka (list topics + consume)
```bash
docker compose exec kafka kafka-topics --bootstrap-server localhost:9092 --list

docker compose exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic url.clicked --from-beginning
```
