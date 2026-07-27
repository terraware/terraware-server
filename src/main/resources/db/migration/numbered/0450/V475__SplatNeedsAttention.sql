ALTER TABLE splats ADD COLUMN needs_attention BOOLEAN;
UPDATE splats SET needs_attention = FALSE;
ALTER TABLE splats ALTER COLUMN needs_attention SET NOT NULL;
