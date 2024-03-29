ALTER TABLE accessions ADD COLUMN primary_collector_name TEXT;
ALTER TABLE accession_secondary_collectors ADD COLUMN id BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY;
ALTER TABLE accession_secondary_collectors ADD COLUMN name TEXT;
CREATE INDEX ON accession_secondary_collectors (accession_id);

UPDATE accessions SET primary_collector_name = (
    SELECT name
    FROM collectors
    WHERE id = accessions.primary_collector_id
);

UPDATE accession_secondary_collectors SET name = (
    SELECT name
    FROM collectors
    WHERE id = accession_secondary_collectors.collector_id
);

ALTER TABLE accession_secondary_collectors DROP COLUMN collector_id;
ALTER TABLE accessions DROP COLUMN primary_collector_id;
DROP TABLE collectors;
