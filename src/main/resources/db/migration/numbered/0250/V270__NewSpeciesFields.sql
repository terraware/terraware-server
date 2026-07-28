-- Text fields
ALTER TABLE species ADD COLUMN ecological_role_known TEXT;
ALTER TABLE species ADD COLUMN dbh_source TEXT;
ALTER TABLE species ADD COLUMN height_at_maturity_source TEXT;
ALTER TABLE species ADD COLUMN local_uses_known TEXT;
ALTER TABLE species ADD COLUMN native_ecosystem TEXT;
ALTER TABLE species ADD COLUMN other_facts TEXT;

-- Number fields
ALTER TABLE species ADD COLUMN average_wood_density NUMERIC;
ALTER TABLE species ADD COLUMN height_at_maturity_value NUMERIC;
ALTER TABLE species ADD COLUMN dbh_value NUMERIC;

-- Dropdown fields
-- Wood density level enum table
CREATE TABLE public.wood_density_levels (
    id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL
);

ALTER TABLE species ADD COLUMN wood_density_level_id INTEGER REFERENCES wood_density_levels;
