CREATE TABLE species_growth_form (
    species_id INTEGER REFERENCES species,
    growth_form_id INTEGER REFERENCES growth_forms,

    PRIMARY KEY (species_id, growth_form_id)
);

CREATE INDEX ON species_growth_forms (species_id);
