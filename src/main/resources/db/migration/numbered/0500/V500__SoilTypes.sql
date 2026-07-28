CREATE TABLE tracking.soil_types (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

ALTER TABLE tracking.observation_biomass_details
    ADD COLUMN soil_type_id INTEGER REFERENCES tracking.soil_types;
