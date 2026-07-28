ALTER TABLE nursery.batches
    ALTER COLUMN hardening_off_quantity SET NOT NULL,
    ALTER COLUMN latest_observed_hardening_off_quantity SET NOT NULL
;

ALTER TABLE nursery.batch_quantity_history
    ALTER COLUMN hardening_off_quantity SET NOT NULL
;

ALTER TABLE nursery.batch_withdrawals
    ALTER COLUMN hardening_off_quantity_withdrawn SET NOT NULL
;
