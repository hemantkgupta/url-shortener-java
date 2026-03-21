-- Phase TTL: add optional expiry to url_mappings
-- NULL means the mapping never expires.

ALTER TABLE url_mappings
    ADD COLUMN IF NOT EXISTS expires_at TIMESTAMP NULL;

-- Partial index: only indexes rows that have an expiry set.
-- Used by the cleanup scheduler (WHERE expires_at < NOW()) and by
-- the read-api expiry check (WHERE short_code = ? AND expires_at > NOW()).
-- Rows with expires_at IS NULL are never scanned by either query.
CREATE INDEX IF NOT EXISTS idx_url_mappings_expires_at
    ON url_mappings (expires_at)
    WHERE expires_at IS NOT NULL;
