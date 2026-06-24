ALTER TABLE seedbank.accessions
    ADD COLUMN collected_time TIMESTAMP WITH TIME ZONE;

UPDATE seedbank.accessions
    SET collected_time = collected_date::TIMESTAMP AT TIME ZONE 'UTC';
