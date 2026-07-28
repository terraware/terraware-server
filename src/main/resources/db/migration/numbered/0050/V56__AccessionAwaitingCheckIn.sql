INSERT INTO accession_states (id, name, active)
VALUES (5, 'Awaiting Check-In', true);

ALTER TABLE accessions ADD COLUMN checked_in_time TIMESTAMP WITH TIME ZONE;
