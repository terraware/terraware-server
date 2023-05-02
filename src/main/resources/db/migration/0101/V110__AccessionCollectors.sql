CREATE TABLE accession_collectors (
    accession_id BIGINT NOT NULL REFERENCES accessions ON DELETE CASCADE,
    position INTEGER NOT NULL,
    name TEXT NOT NULL,
    PRIMARY KEY (accession_id, position),
    CHECK (position >= 0)
);

-- Add cascading deletes to accession_secondary_collectors so we can get rid of the DELETE statement
-- on that table in accession deletion; this will make it safer to stop writing to
-- accession_secondary_collectors in a later code change.
ALTER TABLE accession_secondary_collectors
    ADD CONSTRAINT accession_secondary_collectors_accession_id_fkey
        FOREIGN KEY (accession_id) REFERENCES accessions ON DELETE CASCADE;
ALTER TABLE accession_secondary_collectors
    DROP CONSTRAINT accession_secondary_collector_accession_id_fkey;
