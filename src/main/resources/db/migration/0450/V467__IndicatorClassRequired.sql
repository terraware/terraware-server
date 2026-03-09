UPDATE accelerator.common_indicators
SET class_id = 2
WHERE class_id IS NULL;

UPDATE accelerator.project_indicators
SET class_id = 2
WHERE class_id IS NULL;

-- This is just for setting the column to non-nullable, since these are overridden in R__TypeCodes.sql
UPDATE accelerator.auto_calculated_indicators
SET class_id = 2
WHERE class_id IS NULL;

ALTER TABLE accelerator.common_indicators
ALTER COLUMN class_id SET NOT NULL;

ALTER TABLE accelerator.project_indicators
ALTER COLUMN class_id SET NOT NULL;

ALTER TABLE accelerator.auto_calculated_indicators
ALTER COLUMN class_id SET NOT NULL;
