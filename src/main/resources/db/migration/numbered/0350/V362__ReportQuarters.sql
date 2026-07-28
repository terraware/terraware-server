CREATE TABLE accelerator.report_quarters (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

-- Also in R__TypeCodes.sql
INSERT INTO accelerator.report_quarters (id, name)
VALUES (1, 'Q1'),
       (2, 'Q2'),
       (3, 'Q3'),
       (4, 'Q4')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

ALTER TABLE accelerator.reports
    ADD COLUMN report_frequency_id INTEGER REFERENCES accelerator.report_frequencies,
    ADD COLUMN report_quarter_id INTEGER REFERENCES accelerator.report_quarters;

ALTER TABLE accelerator.project_report_configs ADD UNIQUE (id, report_frequency_id);

-- Set frequency_id for all reports
UPDATE accelerator.reports
SET report_frequency_id = (
    SELECT c.report_frequency_id
    FROM accelerator.project_report_configs AS c
    WHERE c.id = config_id
    LIMIT 1
);

-- Add foreign key to check that config/frequency pair matches up
ALTER TABLE accelerator.reports
    ALTER COLUMN report_frequency_id SET NOT NULL,
    ADD FOREIGN KEY (config_id, report_frequency_id)
        REFERENCES accelerator.project_report_configs(id, report_frequency_id);

-- Set quarter_id for all quarterly reports
UPDATE accelerator.reports
    SET report_quarter_id = EXTRACT(QUARTER FROM start_date)
    WHERE report_frequency_id = 1;

-- Add constraint that report quarters must line up with dates, and present for quarterly reports
-- This also has the benefit of checking that both start and end date are in the same quarter
ALTER TABLE accelerator.reports
    ADD CONSTRAINT quarterly_report_has_quarter
    CHECK ((report_frequency_id = 1
                AND report_quarter_id IS NOT NULL
                AND report_quarter_id = EXTRACT(QUARTER FROM start_date)
                AND report_quarter_id = EXTRACT(QUARTER FROM end_date)) OR
           (report_frequency_id != 1 AND report_quarter_id IS NULL));
