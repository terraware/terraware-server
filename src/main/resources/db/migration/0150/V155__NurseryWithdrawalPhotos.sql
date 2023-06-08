-- No cascading deletes on these foreign keys because deleting photos requires deleting files from
-- the file store.
CREATE TABLE nursery.withdrawal_photos
(
    photo_id      BIGINT PRIMARY KEY REFERENCES photos,
    withdrawal_id BIGINT NOT NULL REFERENCES nursery.withdrawals
);

CREATE INDEX ON nursery.withdrawal_photos (withdrawal_id);
