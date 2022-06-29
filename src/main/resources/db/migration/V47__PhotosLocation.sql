-- Other geography-related columns are added in the V45 migration.

ALTER TABLE photos ADD COLUMN location geometry(POINTZ, 3857);

UPDATE photos
SET location = st_pointonsurface(features.geom)
FROM feature_photos
         JOIN features ON feature_photos.photo_id = features.id
WHERE feature_photos.photo_id = photos.id;

-- Copy GPS coordinates from accession photos, transforming to spherical Mercator.
UPDATE photos
SET location = st_transform(
        st_setsrid(st_makepoint(accession_photos.longitude, accession_photos.latitude, 0), 4326),
        3857)
FROM accession_photos
WHERE accession_photos.photo_id = photos.id;

ALTER TABLE accession_photos DROP COLUMN latitude;
ALTER TABLE accession_photos DROP COLUMN longitude;
