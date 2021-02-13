ALTER TABLE accession_state_history ADD COLUMN reason TEXT;
ALTER TABLE accession_state_history ALTER COLUMN reason SET NOT NULL;
