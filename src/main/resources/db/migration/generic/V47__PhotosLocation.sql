-- On PostgreSQL, we use the PostGIS spatial data types to store GPS coordinates. Replace that with
-- a text column for generic databases.

ALTER TABLE photos
    ADD COLUMN location TEXT;

UPDATE photos
SET location = features.geom
FROM feature_photos
JOIN features ON feature_photos.photo_id = features.id
WHERE feature_photos.photo_id = photos.id;

UPDATE photos
SET location = concat(accession_photos.latitude, ',', accession_photos.longitude, ',0')
FROM accession_photos
WHERE accession_photos.photo_id = photos.id;

ALTER TABLE accession_photos DROP COLUMN latitude;
ALTER TABLE accession_photos DROP COLUMN longitude;
