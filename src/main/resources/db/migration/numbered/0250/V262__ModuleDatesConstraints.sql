CREATE EXTENSION btree_gist;

ALTER TABLE accelerator.cohort_modules
    ADD CONSTRAINT dates_start_before_end CHECK (start_date < end_date);

-- For each cohort, there should not be modules occupying the same dates
ALTER TABLE accelerator.cohort_modules
    ADD CONSTRAINT dates_no_overlap EXCLUDE USING GIST (
        cohort_id WITH =,
        daterange(start_date, end_date, '[]') WITH &&);

ALTER TABLE accelerator.events
    ADD CONSTRAINT times_start_before_end CHECK (start_time < end_time);
