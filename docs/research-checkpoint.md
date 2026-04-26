# HyperShort — Research Checkpoint

## Direction

Build a production-grade URL shortener handling 115,000 redirects/sec with p99 < 10ms, 100M URLs, and 99.99% availability. The core tension: reads are 10,000× writes, and the cache hit rate is existential.

## Foundation

### 1. Short Code Generation (Blog Part 3)
Counter-based via KGS with dual-buffer. XOR + bit-reversal obfuscation before Base62 encoding. 8-character codes from a 47-bit space (~140 trillion values).

**Key files**: `key-gen-service/.../DualBufferKeyGenService.java`, `key-gen-service/.../UrlCodec.java`

### 2. Redirect Pipeline (Blog Part 5)
4-layer cache: Bloom filter → L1 Caffeine (~1µs) → L2 Redis (~1ms, XFetch) → PostgreSQL (~5ms). Each layer fails open to the next.

**Key files**: `read-api/.../RedirectService.java`, `read-api/.../BloomFilterService.java`

### 3. CDC Cache Warming (Blog Part 5)
Debezium tails PostgreSQL WAL → Kafka → Redis. Warms both forward (shortCode→longUrl) and reverse (longUrl→shortCode) keys. TTL derived from `expires_at`.

**Key files**: `cdc-worker/.../UrlMappingChangeHandler.java`, `cdc-worker/.../DebeziumConfig.java`

### 4. Analytics Pipeline (Blog Part 6)
Async fire-and-forget to Kafka → analytics-worker → ClickHouse. Never blocks the redirect response. ClickHouse MergeTree ORDER BY (short_code, clicked_at) for co-located range scans.

**Key files**: `analytics-worker/.../UrlEventConsumer.java`, `flink-job/.../UrlAnalyticsJob.java`

## Going Deeper

### XFetch (Blog Part 5)
Probabilistic early refresh: `threshold = ttl × 0.1 × random × beta`. As TTL drains, probability of refresh rises. One request falls through to DB; all others serve cached data. Zero locks, zero coordination. Eliminates thundering herd on popular URL expiry.

### 302 + Cache-Control (Blog Part 4)
302 Found with `Cache-Control: public, max-age=86400, immutable`. CDN caches for 24h. On deletion: set `max-age=0` + CDN purge. 301 is irreversible — browser cache is permanent and uncontrollable.

### Bloom Filter on Both Paths (Blog Part 5)
Write path: `BF.ADD` immediately after creation (before CDC lag). Read path: `BF.EXISTS` rejects non-existent codes before any cache/DB lookup. Both fail-open.

### 404 vs 410 Semantics (Blog Part 5)
404 = never existed (crawlers retry). 410 = existed and removed (crawlers drop from index). Expired TTL → 410. User deletion → 410. Abuse takedown → 451.

## Recommended Defaults

| Parameter | Default | Rationale |
|---|---|---|
| Code length | 8 chars | 62^8 = 218 trillion. Decades of headroom. |
| KGS block size | 10,000 | One DB round-trip per 10K URLs |
| Prefetch threshold | 80% | Trigger background fetch at 80% consumption |
| Bloom filter FPR | 1% | 10 bits/entry, 125 MB for 100M codes |
| L1 cache max entries | 10,000 | Top 10% of codes = 90%+ traffic (power law) |
| L1 TTL | 60 seconds | Bounds staleness after CDC-lag deletions |
| L2 Redis TTL | 24 hours | CDN-aligned; XFetch prevents herd at expiry |
| XFetch beta | 1.0 | Standard aggressiveness; tune up for hot keys |
| Redis timeout | 10ms | Fail-open: slow Redis = unavailable Redis |
| Redirect status | 302 Found | NOT 301. Analytics, deletions, abuse control. |
| Analytics delivery | Kafka async | Never block the redirect path |

## Wiki Sources

| Source | Coverage |
|---|---|
| `url-shortener.md` (blog) | 12-part architecture guide |
| `system_design_blog.md` (docs) | Design decisions and rationale |
| `architecture.md` (docs) | System topology and data flows |
| `tech-spec.md` (docs) | Current behavior, gaps, cache strategy |
| `api-reference.md` (docs) | REST endpoint specifications |
