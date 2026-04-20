# HyperShort — Code Companion

This file is the code-first companion for the `CSE-Raw` wiki and long-form URL shortener writing. Use it when changing code, syncing the wiki/blog, or handing the topic to Claude or Codex for follow-up work.

## Canonical Companions

The narrative and synthesis live outside this repo:

```text
CSE-Raw/raw-blog/url-shortener.md
CSE-Raw/wiki/implementations/url-shortener-2.md
CSE-Raw/wiki/implementations/url-shortener.md
CSE-Raw/wiki/concepts/short-code-generation.md
CSE-Raw/wiki/concepts/redirect-strategy.md
CSE-Raw/wiki/concepts/bloom-filter.md
CSE-Raw/wiki/concepts/cache-invalidation.md
CSE-Raw/wiki/concepts/xfetch.md
CSE-Raw/wiki/patterns/cdc-cache-warming.md
```

This repo should stay focused on executable behavior, deployable docs, and module-level technical notes.

## Current Truth In Code

- `write-api` dedupes in this order for generated links: `Redis GET url:{longUrl}` -> PostgreSQL `findByLongUrl()` -> create new mapping.
- `write-api` does **not** inline-warm Redis after creating a mapping. It immediately `BF.ADD`s the new short code and relies on `cdc-worker` to warm Redis from PostgreSQL WAL.
- `read-api` resolves in this order: Bloom filter -> Caffeine L1 -> Redis L2 -> PostgreSQL fallback -> async Kafka click event.
- `read-api` distinguishes `404` (never existed) from `410 Gone` (expired but previously existed).
- `read-api` returns `302 Found` with `Cache-Control: public, max-age=86400, immutable`.
- XFetch uses the simplified threshold `ttlSeconds * 0.1 * random * beta`, not the canonical `beta * delta * -ln(U)` form.
- `cdc-worker` writes both `url:{shortCode}` and `url:{longUrl}` keys and derives TTL from `expires_at` when present.
- `analytics-worker` consumes `url.created` and `url.clicked`. `url.created.cdc` is published but not consumed inside this repo.
- `key-gen-service` defaults to PostgreSQL-backed `DUAL_BUFFER`; `SNOWFLAKE` remains a switchable alternative.
- Envoy + `rls-service` exist as optional rate-limited entry points, but the public `:8000` Nginx gateway still routes directly to `write-api` and `read-api`.
- Docker Compose includes request ID propagation, OpenTelemetry Java agents, and SigNoz.

## Topic-To-Module Map

| Topic | Primary module(s) | Key file(s) |
|---|---|---|
| Generated code creation | `write-api`, `key-gen-service` | `write-api/src/main/java/com/urlshortener/writeapi/service/UrlShortenServiceImpl.java`, `key-gen-service/src/main/java/com/urlshortener/keygenservice/service/DualBufferKeyGenService.java` |
| Public code encoding | `write-api`, `key-gen-service` | `write-api/src/main/java/com/urlshortener/writeapi/util/UrlCodec.java`, `key-gen-service/src/main/java/com/urlshortener/keygenservice/util/UrlCodec.java` |
| Redirect pipeline | `read-api` | `read-api/src/main/java/com/urlshortener/readapi/service/RedirectService.java` |
| L1 cache | `read-api` | `read-api/src/main/java/com/urlshortener/readapi/config/CacheConfig.java` |
| Redirect HTTP semantics | `read-api` | `read-api/src/main/java/com/urlshortener/readapi/controller/RedirectController.java` |
| CDC cache warming | `cdc-worker` | `cdc-worker/src/main/java/com/urlshortener/cdcworker/handler/UrlMappingChangeHandler.java` |
| Analytics ingest | `analytics-worker` | `analytics-worker/src/main/java/com/urlshortener/analyticsworker/consumer/UrlEventConsumer.java` |
| Analytics queries | `analytics-api` | `analytics-api/src/main/java/com/urlshortener/analyticsapi/service/AnalyticsQueryService.java` |
| Optional rate limiting | `rls-service`, `envoy` | `rls-service/src/main/java/com/urlshortener/rls/service/SlidingWindowRateLimiter.java`, `envoy/envoy-write.yaml`, `envoy/envoy-read.yaml` |
| Public gateway / request IDs | `nginx`, `write-api`, `read-api`, `analytics-api` | `nginx/gateway.conf`, `*/filter/RequestIdFilter.java` |

## Sync Contract

When code changes behavior:

1. Update `README.md` if the public architecture or current capabilities changed.
2. Update `docs/tech-spec.md` if the default behavior, event model, or cache strategy changed.
3. Update this file if the cross-topic truth changed.
4. Update the relevant implementation page in `CSE-Raw/wiki/implementations/`.
5. Update the relevant concept pages in `CSE-Raw/wiki/concepts/` when the code changes a technical claim those pages make.

When the wiki/blog changes a technical claim:

1. Either implement the change here with tests.
2. Or document the gap explicitly in `CSE-Raw/wiki/implementations/url-shortener-2.md`.
3. Do not leave silent drift between prose and code.

## Review Checklist

- Did a change alter the shorten-path dedupe order?
- Did a change alter the redirect-path stage order or default TTLs?
- Did a change introduce or remove a Redis write on the hot path?
- Did a change affect `404` vs `410` semantics or redirect caching headers?
- Did a change alter which Kafka topics are authoritative vs supplemental?
- Did a change change the default key-generation strategy or its block-allocation semantics?
- Did the wiki/blog describe the exact code path that now ships, rather than a generic URL shortener design?
