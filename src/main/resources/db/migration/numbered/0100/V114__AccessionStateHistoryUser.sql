ALTER TABLE accession_state_history ADD COLUMN updated_by BIGINT REFERENCES users(id);
