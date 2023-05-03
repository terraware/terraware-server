-- Seeds remaining is tracked per-accession, not per-withdrawal.
ALTER TABLE accession ADD COLUMN seeds_remaining INTEGER;
ALTER TABLE withdrawal DROP COLUMN seeds_remaining;

-- The user can enter the withdrawal size in grams, and we need to preserve that for later display
-- in addition to computing the number of seeds based on the accession's seed weight estimate.
ALTER TABLE withdrawal ADD COLUMN grams_withdrawn NUMERIC;
COMMENT ON COLUMN withdrawal.grams_withdrawn
    IS 'Null if user specified a seed count instead of a weight.';
COMMENT ON COLUMN withdrawal.seeds_withdrawn
    IS 'If grams_withdrawn is non-null, this will be a computed estimate based on seed weight.';

-- Track when the withdrawal was created and modified; this can differ from the withdrawal date.
ALTER TABLE withdrawal ADD COLUMN created_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE withdrawal ADD COLUMN updated_time TIMESTAMP WITH TIME ZONE;
UPDATE withdrawal
SET created_time = '1970-01-01 00:00:00Z',
    updated_time = '1970-01-01 00:00:00Z'
WHERE created_time IS NULL;
ALTER TABLE withdrawal ALTER COLUMN created_time SET NOT NULL;
ALTER TABLE withdrawal ALTER COLUMN updated_time SET NOT NULL;
