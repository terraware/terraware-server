ALTER TABLE seedbank.withdrawals ADD COLUMN batch_id BIGINT REFERENCES nursery.batches;
