ALTER TABLE accelerator.auto_calculated_indicators
    ADD COLUMN unit TEXT;

CREATE TABLE accelerator.indicator_classes (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE accelerator.indicator_frequencies (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

ALTER TABLE accelerator.project_indicators
    ADD COLUMN primary_data_source TEXT,
    ADD COLUMN notes TEXT,
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN class_id INTEGER REFERENCES accelerator.indicator_classes,
    ADD COLUMN frequency_id INTEGER REFERENCES accelerator.indicator_frequencies;

ALTER TABLE accelerator.standard_indicators
    ADD COLUMN primary_data_source TEXT,
    ADD COLUMN notes TEXT,
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN class_id INTEGER REFERENCES accelerator.indicator_classes,
    ADD COLUMN frequency_id INTEGER REFERENCES accelerator.indicator_frequencies;

ALTER TABLE accelerator.auto_calculated_indicators
    ADD COLUMN primary_data_source TEXT,
    ADD COLUMN notes TEXT,
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN class_id INTEGER REFERENCES accelerator.indicator_classes,
    ADD COLUMN frequency_id INTEGER REFERENCES accelerator.indicator_frequencies;

ALTER TABLE accelerator.project_indicators
    RENAME COLUMN reference TO ref_id;

ALTER TABLE accelerator.standard_indicators
    RENAME COLUMN reference TO ref_id;

ALTER TABLE accelerator.auto_calculated_indicators
    RENAME COLUMN reference TO ref_id;
