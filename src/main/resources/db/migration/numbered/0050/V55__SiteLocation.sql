ALTER TABLE sites ADD COLUMN location geometry(POINTZ, 3857);
ALTER TABLE sites ADD COLUMN created_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE sites ADD COLUMN modified_time TIMESTAMP WITH TIME ZONE;

UPDATE sites
SET location = st_transform(st_setsrid(st_makepoint(longitude, latitude, 0), 4326), 3857),
    created_time = NOW(),
    modified_time = NOW()
WHERE location IS NULL;

ALTER TABLE sites ALTER COLUMN location SET NOT NULL;
ALTER TABLE sites ALTER COLUMN created_time SET NOT NULL;
ALTER TABLE sites ALTER COLUMN modified_time SET NOT NULL;

ALTER TABLE sites DROP COLUMN latitude;
ALTER TABLE sites DROP COLUMN longitude;
