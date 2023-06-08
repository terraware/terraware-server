CREATE INDEX ON nursery.batch_withdrawals (destination_batch_id);

ALTER TABLE nursery.batch_withdrawals
    ADD CONSTRAINT batch_withdrawals_destination_batch_id_fkey_tmp
        FOREIGN KEY (destination_batch_id)
            REFERENCES nursery.batches (id)
            ON DELETE SET NULL;

ALTER TABLE nursery.batches
    ADD CONSTRAINT batches_accession_id_fkey_tmp
        FOREIGN KEY (accession_id)
            REFERENCES seedbank.accessions (id)
            ON DELETE SET NULL;

ALTER TABLE nursery.withdrawals
    ADD CONSTRAINT withdrawals_destination_only_for_transfers
        CHECK (destination_facility_id IS NULL OR purpose_id = 1);

ALTER TABLE nursery.withdrawals
    ADD CONSTRAINT withdrawals_destination_facility_id_fkey_tmp
        FOREIGN KEY (destination_facility_id)
            REFERENCES facilities (id)
            ON DELETE SET NULL;

ALTER TABLE nursery.batch_withdrawals
    DROP CONSTRAINT batch_withdrawals_destination_batch_id_fkey;
ALTER TABLE nursery.batches
    DROP CONSTRAINT batches_accession_id_fkey;
ALTER TABLE nursery.withdrawals
    DROP CONSTRAINT withdrawals_check;
ALTER TABLE nursery.withdrawals
    DROP CONSTRAINT withdrawals_destination_facility_id_fkey;

ALTER TABLE nursery.batch_withdrawals
    RENAME CONSTRAINT batch_withdrawals_destination_batch_id_fkey_tmp
        TO batch_withdrawals_destination_batch_id_fkey;
ALTER TABLE nursery.batches
    RENAME CONSTRAINT batches_accession_id_fkey_tmp
        TO batches_accession_id_fkey;
ALTER TABLE nursery.withdrawals
    RENAME CONSTRAINT withdrawals_destination_facility_id_fkey_tmp
        TO withdrawals_destination_facility_id_fkey;
