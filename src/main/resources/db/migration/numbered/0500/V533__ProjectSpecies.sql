CREATE TABLE project_species (
    organization_id BIGINT NOT NULL REFERENCES organizations ON DELETE CASCADE,
    project_id BIGINT REFERENCES projects ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES species ON DELETE CASCADE,
    calculated_nativity_id INTEGER REFERENCES species_nativities,
    calculated_nativity_dataset_type_id INTEGER REFERENCES external_dataset_types,
    calculated_nativity_dataset_date DATE,
    overridden_nativity_id INTEGER REFERENCES species_nativities,
    overridden_justification TEXT,
    overridden_by BIGINT REFERENCES users,
    overridden_time TIMESTAMP WITH TIME ZONE,

    CONSTRAINT nativity_dataset_has_both_values
        CHECK (
            (calculated_nativity_dataset_date IS NULL) =
               (calculated_nativity_dataset_type_id IS NULL)
        ),

    -- Nativity 4 = Unknown
    CONSTRAINT known_nativity_has_dataset
        CHECK (
            ((calculated_nativity_id = 4 OR calculated_nativity_id IS NULL)
                AND calculated_nativity_dataset_date IS NULL)
            OR calculated_nativity_dataset_date IS NOT NULL
        )
);

CREATE UNIQUE INDEX ON project_species (
    organization_id,
    project_id,
    species_id
) NULLS NOT DISTINCT;

CREATE INDEX ON project_species (project_id);
CREATE INDEX ON project_species (species_id);
