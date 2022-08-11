-- We know for sure which user created the accession.
UPDATE accession_state_history
SET updated_by = (SELECT created_by
                  FROM accessions
                  WHERE accessions.id = accession_state_history.accession_id)
WHERE old_state_id IS NULL
  AND updated_by IS NULL;

-- But after that, we can't tell if state changes were automated or user-initiated, so attribute
-- them all to the system user rather than possibly attributing them to the wrong human.
UPDATE accession_state_history
SET updated_by = (SELECT id FROM users WHERE user_type_id = 4)
WHERE updated_by IS NULL;

ALTER TABLE accession_state_history ALTER COLUMN updated_by SET NOT NULL;
