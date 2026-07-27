CREATE TABLE data_sources (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

ALTER TABLE accessions ADD COLUMN data_source_id INTEGER REFERENCES data_sources;
