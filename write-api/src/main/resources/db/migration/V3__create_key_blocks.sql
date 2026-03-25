-- key_blocks: single-row counter used by the key generation service.
-- The row is shared infrastructure so the generator can reserve non-overlapping
-- ID blocks with a single atomic UPDATE ... RETURNING.

CREATE TABLE IF NOT EXISTS key_blocks (
    id BIGINT PRIMARY KEY DEFAULT 1,
    next_id BIGINT NOT NULL DEFAULT 1
);

INSERT INTO key_blocks (id, next_id)
VALUES (1, 1)
ON CONFLICT DO NOTHING;
