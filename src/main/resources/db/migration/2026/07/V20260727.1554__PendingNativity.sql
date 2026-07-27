ALTER TABLE project_species
    ADD COLUMN pending_nativity_id INTEGER REFERENCES species_nativities,
    ADD COLUMN pending_nativity_dataset_date DATE,
    ADD COLUMN pending_nativity_dataset_type_id INTEGER REFERENCES external_dataset_types,

    ADD CONSTRAINT pending_nativity_dataset_has_both_values
        CHECK (
            (pending_nativity_dataset_date IS NULL) =
            (pending_nativity_dataset_type_id IS NULL)
        ),

    -- Nativity 4 = Unknown
    ADD CONSTRAINT known_pending_nativity_has_dataset
        CHECK (
            ((pending_nativity_id = 4 OR pending_nativity_id IS NULL)
            AND pending_nativity_dataset_date IS NULL)
            OR pending_nativity_dataset_date IS NOT NULL
        );
