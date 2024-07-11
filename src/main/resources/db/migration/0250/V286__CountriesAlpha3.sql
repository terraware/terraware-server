ALTER TABLE countries ADD COLUMN code_alpha3 TEXT UNIQUE;
ALTER TABLE countries ADD COLUMN eligible BOOLEAN;

-- This will be overwritten by R__Countries.sql but will let us add the not-null constraints.
UPDATE countries SET code_alpha3 = code, eligible = TRUE;

ALTER TABLE countries ALTER COLUMN code_alpha3 SET NOT NULL;
ALTER TABLE countries ALTER COLUMN eligible SET NOT NULL;
