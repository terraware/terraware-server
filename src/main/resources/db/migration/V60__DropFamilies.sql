-- Make family name a simple per-accession text field to reflect how it works in the UI. When we
-- add taxonomic information to our species data model, it won't work the way the families table
-- works now, and having this families table with its "sort of per-accession, sort of per-species"
-- behavior makes it harder to update the species data model.
ALTER TABLE accessions ADD COLUMN family_name TEXT;

UPDATE accessions a
SET family_name = (
    SELECT f.name
    FROM families f
    WHERE f.id = a.family_id
)
WHERE a.family_id IS NOT NULL;

ALTER TABLE accessions DROP COLUMN family_id;
ALTER TABLE species DROP COLUMN family_id;
DROP TABLE family_names;
DROP TABLE families;
