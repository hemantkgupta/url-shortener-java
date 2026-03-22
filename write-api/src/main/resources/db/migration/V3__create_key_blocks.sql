-- key_blocks: single-row counter used by key-gen-service (dual-buffer strategy).
-- Reserving a block is a single atomic UPDATE … RETURNING — no gap, no contention.
-- Multiple key-gen-service instances each get their own non-overlapping range.

CREATE TABLE IF NOT EXISTS key_blocks (
    id      BIGINT PRIMARY KEY DEFAULT 1,
    next_id BIGINT NOT NULL    DEFAULT 1
);

-- Seed the one and only counter row
INSERT INTO key_blocks (id, next_id)
VALUES (1, 1)
ON CONFLICT DO NOTHING;
