-- Set new column sequence to number orderings
BEGIN;
ALTER TABLE accelerator.cohort_modules ADD COLUMN title TEXT;
UPDATE accelerator.cohort_modules t
SET title = CONCAT('Module ', s.row_num::TEXT)
    FROM (
        SELECT cohort_id, start_date, ROW_NUMBER() OVER (PARTITION BY cohort_id ORDER BY start_date) AS row_num
        FROM accelerator.cohort_modules
    ) AS s
WHERE t.cohort_id = s.cohort_id AND t.start_date = s.start_date;

ALTER TABLE accelerator.cohort_modules ALTER COLUMN title SET NOT NULL;
COMMIT;
