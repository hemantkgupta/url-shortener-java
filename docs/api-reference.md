# API Reference

All endpoints are served through the **Nginx Gateway on port 8000**.

Base URL (local): `http://localhost:8000`

---

## Authentication

Endpoints that require auth expect a Google-issued JWT in the `Authorization` header:

```
Authorization: Bearer <google-id-token>
```

Spring Security validates the token automatically against Google's JWKS endpoint (`https://www.googleapis.com/oauth2/v3/certs`).

---

## Write API

### Shorten a URL

```
POST /api/v1/shorten
```

Creates a short URL. Authentication is optional — anonymous users get a short URL with no owner.

**Request body**
```json
{
  "long_url": "https://example.com/some/very/long/path",
  "custom_slug": "my-slug"   // optional, 1-16 chars, letters/numbers/-/_
}
```

**Response — 201 Created**
```json
{
  "short_code": "4jTh9q",
  "short_url":  "http://localhost:8000/4jTh9q",
  "long_url":   "https://example.com/some/very/long/path"
}
```

**Response — 400 Bad Request** (invalid or unsupported input)
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "long_url must use http or https"
}
```

**Response — 409 Conflict** (custom slug already in use)
```json
{
  "status": 409,
  "error": "Conflict",
  "message": "custom_slug is already in use"
}
```

**Notes**:
- Submitting the same `long_url` twice returns the existing short code (idempotent)
- `custom_slug` is supported for new links; reserved paths like `api` and `my-links` are rejected
- `short_url` base is the gateway URL (`http://localhost:8000`) not the read-api URL

---

## Read API

### Redirect a Short URL

```
GET /{shortCode}
```

Resolves the short code and redirects the caller. No auth required.

**Response — 302 Found**
```
Location: https://example.com/some/very/long/path
```

**Response — 404 Not Found** *(handled by gateway — serves React SPA)*

**Notes**:
- Every successful redirect publishes a `url.clicked` event to Kafka
- Redis cache is checked first; DB is only hit on cache miss

---

## Analytics API

### Top URLs by Click Count

```
GET /api/v1/analytics/top
```

Returns the most-clicked URLs. Public — no auth required.

**Query parameters**

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `page` | int | 1 | Page number (1-based) |
| `limit` | int | 10 | Results per page (max 100) |

**Response — 200 OK**
```json
[
  {
    "short_code": "4jTh9q",
    "long_url":   "https://example.com",
    "click_count": 1042
  },
  {
    "short_code": "aB3xKp",
    "long_url":   "https://news.ycombinator.com",
    "click_count": 837
  }
]
```

---

### User Link History

```
GET /api/v1/history
Authorization: Bearer <google-id-token>
```

Returns all URLs created by the authenticated user, newest first.

**Response — 200 OK**
```json
[
  {
    "short_code": "4jTh9q",
    "long_url":   "https://example.com",
    "created_at": "2026-03-20T10:30:00Z"
  }
]
```

**Response — 401 Unauthorized** (missing or invalid JWT)
```json
{}
```

---

## Health Checks

Each service exposes Spring Actuator health at `/actuator/health`. Through the gateway:

```
GET http://localhost:8000/actuator/health   → write-api health
GET http://localhost:8080/actuator/health  → write-api (direct)
GET http://localhost:8081/actuator/health  → read-api (direct)
GET http://localhost:8083/actuator/health  → analytics-api (direct)
```

**Response — 200 OK**
```json
{ "status": "UP" }
```

---

## Error Format

All 4xx/5xx responses from the Java services follow this shape:

```json
{
  "timestamp": "2026-03-20T10:30:00.000Z",
  "status": 404,
  "error": "Short code 'xyz' not found"
}
```

---

## Curl Examples

```bash
# Shorten a URL (anonymous)
curl -X POST http://localhost:8000/api/v1/shorten \
  -H "Content-Type: application/json" \
  -d '{"long_url": "https://github.com/hemantkgupta/url-shortener-java"}'

# Shorten a URL (authenticated)
curl -X POST http://localhost:8000/api/v1/shorten \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <your-google-id-token>" \
  -d '{"long_url": "https://github.com/hemantkgupta/url-shortener-java"}'

# Follow a short link
curl -L http://localhost:8000/4jTh9q

# Top performing links
curl http://localhost:8000/api/v1/analytics/top?page=1&limit=5

# User history
curl http://localhost:8000/api/v1/history \
  -H "Authorization: Bearer <your-google-id-token>"
```
