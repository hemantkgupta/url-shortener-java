-- Phase 7: Initial schema — url_mappings table
-- Flyway runs this once on first startup; subsequent restarts are no-ops.

CREATE TABLE IF NOT EXISTS url_mappings (
    id         BIGSERIAL       PRIMARY KEY,
    long_url   VARCHAR(2048)   NOT NULL,
    short_code VARCHAR(16)     NOT NULL,
    user_id    VARCHAR(128),
    created_at TIMESTAMP       NOT NULL,

    CONSTRAINT uk_url_mappings_short_code UNIQUE (short_code)
);

-- Fast lookup by long_url (used by write-api to detect duplicate submissions)
CREATE INDEX IF NOT EXISTS idx_url_mappings_long_url
    ON url_mappings (long_url);

-- Fast lookup by user_id (used by analytics-api for /api/v1/history)
CREATE INDEX IF NOT EXISTS idx_url_mappings_user_id
    ON url_mappings (user_id)
    WHERE user_id IS NOT NULL;
