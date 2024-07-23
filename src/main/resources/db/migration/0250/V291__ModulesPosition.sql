ALTER TABLE accelerator.modules ADD COLUMN position INTEGER;

UPDATE accelerator.modules SET position = id;

ALTER TABLE accelerator.modules ALTER COLUMN position SET NOT NULL;
