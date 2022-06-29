-- Rework the way we handle canonical species names: we may not know the scientific name for a
-- species, but we want to require it to have SOME canonical name.

ALTER TABLE species RENAME COLUMN scientific_name TO name;
ALTER TABLE species ADD COLUMN is_scientific BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE species_names
SET is_scientific = FALSE
WHERE is_scientific IS NULL;

ALTER TABLE species_names ALTER COLUMN is_scientific SET DEFAULT FALSE;
ALTER TABLE species_names ALTER COLUMN is_scientific SET NOT NULL;
ALTER TABLE species_names ADD CONSTRAINT species_names_unique UNIQUE (species_id, name);

ALTER TABLE families RENAME COLUMN scientific_name TO name;
ALTER TABLE families ADD COLUMN is_scientific BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE family_names ADD COLUMN is_scientific BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE family_names ADD CONSTRAINT family_names_unique UNIQUE (family_id, name);

COMMENT ON CONSTRAINT species_names_unique ON species_names
    IS 'Disallow duplicate names for the same species.';
COMMENT ON CONSTRAINT family_names_unique ON family_names
    IS 'Disallow duplicate names for the same family.';
