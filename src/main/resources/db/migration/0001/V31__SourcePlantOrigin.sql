CREATE TABLE source_plant_origin (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    UNIQUE (name)
);

ALTER TABLE accession ADD COLUMN source_plant_origin_id INTEGER REFERENCES source_plant_origin (id);
