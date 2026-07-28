CREATE TABLE ecosystem_types (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE species_ecosystem_types (
    species_id BIGINT NOT NULL REFERENCES species ON DELETE CASCADE,
    ecosystem_type_id INTEGER NOT NULL REFERENCES ecosystem_types,
    PRIMARY KEY (species_id, ecosystem_type_id)
);
