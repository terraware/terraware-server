ALTER TABLE accelerator.participant_project_species ADD COLUMN internal_comment TEXT;

CREATE TABLE species_native_categories (
    id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL
);

ALTER TABLE accelerator.participant_project_species ADD COLUMN species_native_category_id INTEGER REFERENCES species_native_categories;
