-- Update existing data to have the `Submitted` status when submitted_by is not null.
UPDATE reports
    SET status_id = 4
    WHERE submitted_by IS NOT NULL;

-- Add constraint that makes sure the status correctly reflects whether the report is submitted.
ALTER TABLE reports
    ADD CONSTRAINT status_reflects_submitted
        CHECK (submitted_by IS NULL AND status_id <> 4
            OR submitted_by IS NOT NULL AND status_id = 4);
