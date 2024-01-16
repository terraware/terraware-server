ALTER TABLE seedbank.withdrawals ADD COLUMN batch_id BIGINT NOT NULL REFERENCES nursery.batches;
