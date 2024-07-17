ALTER TABLE accelerator.applications ALTER COLUMN internal_name DROP NOT NULL;
ALTER TABLE accelerator.applications ADD UNIQUE (internal_name);
