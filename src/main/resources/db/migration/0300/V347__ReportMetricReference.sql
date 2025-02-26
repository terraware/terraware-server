ALTER TABLE accelerator.standard_metrics
    DROP COLUMN reference,
    ADD COLUMN reference INTEGER NOT NULL CHECK (reference >= 0),
    ADD COLUMN sub_reference INTEGER CHECK (sub_reference >= 0),
    ADD COLUMN sub_sub_reference INTEGER CHECK (sub_sub_reference >= 0);

ALTER TABLE accelerator.project_metrics
    DROP COLUMN reference,
    ADD COLUMN reference INTEGER NOT NULL CHECK (reference >= 0),
    ADD COLUMN sub_reference INTEGER CHECK (sub_reference >= 0),
    ADD COLUMN sub_sub_reference INTEGER CHECK (sub_sub_reference >= 0);
