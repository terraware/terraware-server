-- Add new columns
ALTER TABLE accelerator.auto_calculated_indicators
    ADD COLUMN precision INTEGER;

ALTER TABLE accelerator.common_indicators
    ADD COLUMN precision INTEGER;

ALTER TABLE accelerator.project_indicators
    ADD COLUMN precision INTEGER;

-- Input default values for new columns
UPDATE accelerator.auto_calculated_indicators
SET precision = 0;

UPDATE accelerator.common_indicators
SET precision = 0;

UPDATE accelerator.project_indicators
SET precision = 0;

ALTER TABLE accelerator.auto_calculated_indicators
    ALTER COLUMN precision SET NOT NULL;

ALTER TABLE accelerator.common_indicators
    ALTER COLUMN precision SET NOT NULL;

ALTER TABLE accelerator.project_indicators
    ALTER COLUMN precision SET NOT NULL;
