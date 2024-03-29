CREATE TABLE species_problem_fields (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE species_problem_types (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE species_problems (
    id BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    species_id BIGINT NOT NULL REFERENCES species,
    field_id INTEGER NOT NULL REFERENCES species_problem_fields,
    type_id INTEGER NOT NULL REFERENCES species_problem_types,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    suggested_value TEXT
);

CREATE INDEX ON species_problems (species_id);

ALTER TABLE species ADD COLUMN checked_time TIMESTAMP WITH TIME ZONE;
