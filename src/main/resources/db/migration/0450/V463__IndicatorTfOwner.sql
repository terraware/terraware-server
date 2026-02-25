ALTER TABLE accelerator.project_indicators
    ADD COLUMN tf_owner TEXT;

ALTER TABLE accelerator.common_indicators
    ADD COLUMN tf_owner TEXT;

ALTER TABLE accelerator.auto_calculated_indicators
    ADD COLUMN tf_owner TEXT;
