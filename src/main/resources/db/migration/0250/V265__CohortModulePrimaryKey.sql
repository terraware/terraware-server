-- Delete duplicated cohort_module assignments, keep only last inserted
DELETE FROM accelerator.cohort_modules a USING accelerator.cohort_modules b
    WHERE b.id < a.id
    AND a.cohort_id = b.cohort_id
    AND a.module_id = b.module_id;

-- Make (cohort_id, module_id) primary key and unique
ALTER TABLE accelerator.cohort_modules DROP CONSTRAINT cohort_modules_pkey;
ALTER TABLE accelerator.cohort_modules ADD PRIMARY KEY (cohort_id, module_id);
ALTER TABLE accelerator.cohort_modules DROP COLUMN id;
