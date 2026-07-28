ALTER TABLE accelerator.project_metrics
    ALTER COLUMN reference TYPE TEXT COLLATE natural_numeric;

ALTER TABLE accelerator.standard_metrics
    ALTER COLUMN reference TYPE TEXT COLLATE natural_numeric;
