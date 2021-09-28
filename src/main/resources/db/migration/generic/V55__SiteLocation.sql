ALTER TABLE sites ADD COLUMN location TEXT;
ALTER TABLE sites ADD COLUMN created_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE sites ADD COLUMN modified_time TIMESTAMP WITH TIME ZONE;

UPDATE sites
SET location = CONCAT(longitude, ',', latitude, ',', 0),
    created_time = NOW(),
    modified_time = NOW()
WHERE location IS NULL;

ALTER TABLE sites ALTER COLUMN location SET NOT NULL;
ALTER TABLE sites ALTER COLUMN created_time SET NOT NULL;
ALTER TABLE sites ALTER COLUMN modified_time SET NOT NULL;

ALTER TABLE sites DROP COLUMN latitude;
ALTER TABLE sites DROP COLUMN longitude;
