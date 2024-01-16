UPDATE seedbank.withdrawals
SET
    batch_id = joined.batch_id
FROM (
    SELECT withdrawals.id withdrawalId, batches.batch_id
     FROM nursery.batch_quantity_history batches
        INNER JOIN seedbank.withdrawals withdrawals
            ON batches.germinating_quantity = withdrawals.withdrawn_quantity
            AND batches.created_by = withdrawals.created_by
            AND date_trunc('second', withdrawals.created_time)
                BETWEEN (date_trunc('second', batches.created_time) - '1 second'::interval)
                AND (date_trunc('second', batches.created_time) + '1 second'::interval)
) as joined
WHERE joined.withdrawalId = seedbank.withdrawals.id;
