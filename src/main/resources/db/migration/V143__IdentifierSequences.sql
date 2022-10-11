CREATE TABLE identifier_sequences (
    organization_id BIGINT PRIMARY KEY REFERENCES organizations ON DELETE CASCADE,
    next_value      BIGINT NOT NULL DEFAULT 1
);

DROP SEQUENCE seedbank.accession_number_seq;

-- Accession numbers should not need to be globally unique.
ALTER TABLE seedbank.accessions
    ADD CONSTRAINT accession_number_facility_unique UNIQUE (number, facility_id);
ALTER TABLE seedbank.accessions
    DROP CONSTRAINT accession_number_unique;
