ALTER TABLE accelerator.project_metrics
    ADD COLUMN is_publishable BOOLEAN;

ALTER TABLE accelerator.standard_metrics
    ADD COLUMN is_publishable BOOLEAN;

ALTER TABLE accelerator.system_metrics
    ADD COLUMN is_publishable BOOLEAN;

UPDATE accelerator.project_metrics
    SET is_publishable = true;

UPDATE accelerator.standard_metrics
    SET is_publishable = true;

UPDATE accelerator.system_metrics
    SET is_publishable = true;

ALTER TABLE accelerator.project_metrics
    ALTER COLUMN is_publishable SET NOT NULL;

ALTER TABLE accelerator.standard_metrics
    ALTER COLUMN is_publishable SET NOT NULL;

ALTER TABLE accelerator.system_metrics
    ALTER COLUMN is_publishable SET NOT NULL;
