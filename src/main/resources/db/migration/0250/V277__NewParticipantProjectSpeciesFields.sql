ALTER TABLE accelerator.participant_project_species ADD COLUMN internal_comment TEXT;

CREATE TABLE native_non_native (
    id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL
);

ALTER TABLE accelerator.participant_project_species ADD COLUMN native_non_native_id INTEGER REFERENCES native_non_native;
