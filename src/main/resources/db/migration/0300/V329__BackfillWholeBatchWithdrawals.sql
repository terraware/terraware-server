-- Find cases where a batch has a single withdrawal of the entire batch, the withdrawal didn't
-- create a quantity history entry, and there are no other quantity history updates; we'll add
-- quantity history entries for those withdrawals and set the remaining quantities to 0.

CREATE TEMPORARY TABLE backfill_withdrawals AS
    SELECT b.id AS batch_id,
           w.id AS withdrawal_id,
           w.created_by AS withdrawal_created_by,
           w.created_time AS withdrawal_created_time,
           b.germinating_quantity,
           b.not_ready_quantity,
           b.ready_quantity
    FROM nursery.batches b
    JOIN nursery.batch_withdrawals bw ON b.id = bw.batch_id
    JOIN nursery.withdrawals w ON bw.withdrawal_id = w.id
    WHERE bw.germinating_quantity_withdrawn = b.germinating_quantity
    AND bw.not_ready_quantity_withdrawn = b.not_ready_quantity
    AND bw.ready_quantity_withdrawn = b.ready_quantity
    AND NOT EXISTS (
       SELECT 1
       FROM nursery.batch_quantity_history bqh
       WHERE bqh.withdrawal_id = w.id
       AND bqh.batch_id = b.id
    )
    AND NOT EXISTS (
       SELECT 1
       FROM nursery.batch_quantity_history bqh
       WHERE bqh.batch_id = b.id
       AND bqh.version > 1
    );

INSERT INTO nursery.batch_quantity_history (
    batch_id,
    history_type_id,
    created_by,
    created_time,
    germinating_quantity,
    not_ready_quantity,
    ready_quantity,
    withdrawal_id,
    version
)
SELECT batch_id,
       2,
       withdrawal_created_by,
       withdrawal_created_time,
       0,
       0,
       0,
       withdrawal_id,
       2
FROM backfill_withdrawals;

UPDATE nursery.batches b
SET germinating_quantity = 0,
    not_ready_quantity = 0,
    ready_quantity = 0,
    modified_by = bw.withdrawal_created_by,
    modified_time = bw.withdrawal_created_time,
    version = 2
FROM backfill_withdrawals bw
WHERE b.id = bw.batch_id;

DROP TABLE backfill_withdrawals;
