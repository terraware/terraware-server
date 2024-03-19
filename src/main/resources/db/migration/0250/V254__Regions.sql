CREATE TABLE regions (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

-- Also in R__TypeCodes.sql.
INSERT INTO regions (id, name)
VALUES (1, 'Antarctica'),
       (2, 'East Asia & Pacific'),
       (3, 'Europe & Central Asia'),
       (4, 'Latin America & Caribbean'),
       (5, 'Middle East & North Africa'),
       (6, 'North America'),
       (7, 'Oceania'),
       (8, 'South Asia'),
       (9, 'Sub-Saharan Africa')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

ALTER TABLE countries ADD COLUMN region_id INTEGER REFERENCES regions;

-- Dummy value so we can make the column non-nullable; actual values are in R__Countries.sql.
UPDATE countries SET region_id = 1;

ALTER TABLE countries ALTER COLUMN region_id SET NOT NULL;
