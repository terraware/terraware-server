ALTER TABLE splats ADD COLUMN data_file_id BIGINT REFERENCES files ON DELETE SET NULL;

CREATE INDEX ON splats (data_file_id);
