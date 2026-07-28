CREATE TABLE tracking.observation_photo_types (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

-- This is also in R__TypeCodes.sql, but is repeated here to ensure migration is successful
INSERT INTO tracking.observation_photo_types (id, name)
VALUES (1, 'Plot'),
       (2, 'Quadrant'),
       (3, 'Soil')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

ALTER TABLE tracking.observation_photos
    ADD COLUMN type_id INTEGER REFERENCES tracking.observation_photo_types;

-- Set all existing photos to be plot photos
UPDATE tracking.observation_photos
    SET type_id = 1;

ALTER TABLE tracking.observation_photos ALTER COLUMN type_id SET NOT NULL;
