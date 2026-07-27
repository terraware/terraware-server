-- Modify the "endangered" and "rare" accession values to add an "unsure" option. Do it using
-- reference tables (rather than a Postgres enum type) in anticipation of the list of options
-- evolving over time.

CREATE TABLE species_endangered_type (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

INSERT INTO species_endangered_type (id, name)
VALUES (0, 'No');
INSERT INTO species_endangered_type (id, name)
VALUES (1, 'Yes');
INSERT INTO species_endangered_type (id, name)
VALUES (2, 'Unsure');

CREATE TABLE species_rare_type (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

INSERT INTO species_rare_type (id, name)
VALUES (0, 'No');
INSERT INTO species_rare_type (id, name)
VALUES (1, 'Yes');
INSERT INTO species_rare_type (id, name)
VALUES (2, 'Unsure');

ALTER TABLE accession ADD COLUMN species_endangered_type_id INTEGER REFERENCES species_endangered_type (id);
ALTER TABLE accession ADD COLUMN species_rare_type_id INTEGER REFERENCES species_rare_type (id);

UPDATE accession
SET species_endangered_type_id = CASE WHEN species_endangered THEN 1 ELSE 0 END
WHERE accession.species_endangered_type_id IS NULL;

UPDATE accession
SET species_rare_type_id = CASE WHEN species_rare THEN 1 ELSE 0 END
WHERE accession.species_rare_type_id IS NULL;

ALTER TABLE accession DROP COLUMN species_endangered;
ALTER TABLE accession DROP COLUMN species_rare;
