ALTER TABLE accelerator.auto_calculated_indicators
    ADD COLUMN precision INTEGER NOT NULL DEFAULT 0;

ALTER TABLE accelerator.common_indicators
    ADD COLUMN precision INTEGER NOT NULL DEFAULT 0;

ALTER TABLE accelerator.project_indicators
    ADD COLUMN precision INTEGER NOT NULL DEFAULT 0;
