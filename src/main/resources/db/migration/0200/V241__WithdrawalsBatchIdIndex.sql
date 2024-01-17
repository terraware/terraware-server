CREATE INDEX ON seedbank.withdrawals (batch_id);

ALTER TABLE seedbank.withdrawals
    DROP CONSTRAINT withdrawals_batch_id_fkey;

ALTER TABLE seedbank.withdrawals
    ADD FOREIGN KEY (batch_id)
        REFERENCES nursery.batches
        ON DELETE SET NULL;
