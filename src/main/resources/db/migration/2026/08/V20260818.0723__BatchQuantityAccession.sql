ALTER TABLE nursery.batch_quantity_history
    ADD COLUMN accession_id BIGINT
        REFERENCES seedbank.accessions (id)
            ON DELETE SET NULL;

UPDATE nursery.batch_quantity_history bqh
    SET accession_id = b.accession_id
    FROM nursery.batches b
    WHERE b.id = bqh.batch_id
    AND b.accession_id IS NOT NULL
    AND bqh.version = 1;

CREATE INDEX ON nursery.batch_quantity_history (accession_id);
