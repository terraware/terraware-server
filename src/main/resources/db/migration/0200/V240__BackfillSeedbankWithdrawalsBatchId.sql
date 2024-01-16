UPDATE seedbank.withdrawals withdrawals
SET batch_id = (
    SELECT batchHistory.batch_id
    FROM nursery.batch_quantity_history batchHistory
    INNER JOIN nursery.batches batches
        ON batches.id = batchHistory.batch_id
    INNER JOIN seedbank.accessions accessions
        ON accessions.id = withdrawals.accession_id
    WHERE batches.species_id = accessions.species_id
        AND batchHistory.germinating_quantity = withdrawals.withdrawn_quantity
        AND batchHistory.created_by = withdrawals.created_by
        AND batchHistory.created_time
            BETWEEN withdrawals.created_time
            AND (withdrawals.created_time + '1 second'::interval)
)
WHERE withdrawals.batch_id IS NULL
    AND withdrawals.purpose_id = 9;
