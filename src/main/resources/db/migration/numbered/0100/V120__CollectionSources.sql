CREATE TABLE collection_sources (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

ALTER TABLE accessions ADD COLUMN collection_source_id INTEGER REFERENCES collection_sources (id);
