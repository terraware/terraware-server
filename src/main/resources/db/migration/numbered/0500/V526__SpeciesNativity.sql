CREATE TABLE species_nativities (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

ALTER TABLE griis_taxa
    ADD COLUMN species_nativity_id INTEGER REFERENCES species_nativities;
ALTER TABLE wcvp_distributions
    ADD COLUMN species_nativity_id INTEGER REFERENCES species_nativities;
