# ADR-0001: Counter-Based KGS with Feistel Obfuscation

**Status**: Accepted  
**Date**: 2026-04-21  

## Context

We need to generate 7-8 character short codes for 100M+ URLs. The codes must be collision-free, non-enumerable, and fast to generate at 1,160 writes/sec peak.

## Decision

**Counter-based generation via a Key Generation Service (KGS) with dual-buffer block allocation, obfuscated via XOR + bit-reversal before Base62 encoding.**

## Alternatives Considered

### 1. Hash the URL (MD5/SHA → first 7 chars)
**Rejected**: Birthday paradox gives ~0.1% collision rate at 100M URLs in a 3.5T space. Non-deterministic write latency from retry loops. No deduplication without a reverse index.

### 2. Auto-Increment + Base62
**Rejected at scale**: Sequential codes leak creation rate (competitive intelligence). Every write requires a DB round-trip for the next ID. Write throughput bottlenecked by counter store.

### 3. UUID → Base62 (first 7 chars)
**Rejected**: UUIDs are 128 bits; truncating to 7 Base62 chars (41 bits) gives high collision rates. No ordering guarantee.

## Why Counter + KGS + Dual-Buffer

- **Zero collisions**: Monotonic counter guarantees uniqueness without retry logic.
- **Decoupled from DB**: KGS fetches blocks of 10,000 IDs. Counter-store round-trips drop from 1-per-URL to 1-per-10,000.
- **Dual-buffer eliminates wait**: When the active buffer hits 80%, a background thread prefetches the next block. Active buffer exhaustion triggers an instant pointer swap to the standby. Callers never wait.
- **Obfuscation**: XOR with a secret key + 47-bit reversal makes consecutive counter values produce visually unrelated codes. Not cryptographically secure, but sufficient for anti-enumeration. For genuine security, upgrade to AES-FFX (Format Preserving Encryption).

## Why 302, Not 301

301 caches permanently in browsers. After a 301, the browser never contacts the server again — analytics are blind, deletions don't work, abuse takedowns are ineffective. 302 + `Cache-Control: public, max-age=86400` gives controlled caching with CDN purge capability.

## Why 4-Layer Redirect Cache (Bloom → L1 → L2 → DB)

At 115,000 redirects/sec, the DB cannot survive unassisted. The cache hit rate is existential:
- **Bloom filter**: 125 MB, rejects ~99% of invalid lookups at zero cost
- **L1 Caffeine**: ~1µs, 10K entries, 60s TTL (absorbs hot traffic)
- **L2 Redis**: ~1ms, 24h TTL, XFetch prevents thundering herd
- **DB**: ~5ms, fallback for cache misses (~1% of traffic)

## Consequences

**Positive**: Zero collisions, O(1) code generation, non-enumerable codes, 99%+ cache hit rate.  
**Negative**: KGS is a critical-path dependency for writes (mitigated by dual-buffer runway). Counter store (PostgreSQL) must be highly available.
