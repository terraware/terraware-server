ALTER TABLE uploads ADD COLUMN facility_id BIGINT REFERENCES facilities (id);

CREATE INDEX ON uploads (facility_id);
