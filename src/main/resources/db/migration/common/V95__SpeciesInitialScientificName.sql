ALTER TABLE species ADD COLUMN initial_scientific_name TEXT;
UPDATE species
SET initial_scientific_name = scientific_name
WHERE species.initial_scientific_name IS NULL;
ALTER TABLE species ALTER COLUMN initial_scientific_name SET NOT NULL;

CREATE INDEX ON species (organization_id, initial_scientific_name);
