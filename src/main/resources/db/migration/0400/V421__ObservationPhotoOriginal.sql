ALTER TABLE tracking.observation_photos ADD COLUMN is_original BOOLEAN;

UPDATE tracking.observation_photos SET is_original = TRUE;

ALTER TABLE tracking.observation_photos ALTER COLUMN is_original SET NOT NULL;
