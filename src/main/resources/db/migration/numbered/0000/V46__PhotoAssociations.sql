-- Add some GPS information to the photos table. The V45 migration adds another column.
ALTER TABLE photos ADD COLUMN gps_horiz_accuracy DOUBLE PRECISION;
ALTER TABLE photos ADD COLUMN gps_vert_accuracy DOUBLE PRECISION;

-- Track the photo's storage location separately from the client-supplied filename.
-- This is safe to add as NOT NULL because there aren't any existing rows yet.
ALTER TABLE photos ADD COLUMN storage_url TEXT NOT NULL UNIQUE;

-- Previously, there was a table for accession photos and a table for GIS feature photos. Use a
-- table for generic information about the photos themselves and a series of linking tables to
-- associate them with specific kinds of objects.
CREATE TABLE feature_photos (
    photo_id BIGINT PRIMARY KEY REFERENCES photos (id),
    feature_id BIGINT NOT NULL REFERENCES features (id),
    plant_observation_id BIGINT REFERENCES plant_observations (id)
);

CREATE INDEX ON feature_photos (feature_id);

INSERT INTO feature_photos
SELECT id, feature_id, plant_observation_id
FROM photos;

UPDATE photos
SET gps_horiz_accuracy = features.gps_horiz_accuracy,
    gps_vert_accuracy  = features.gps_vert_accuracy
FROM feature_photos
         JOIN features ON feature_photos.feature_id = features.id
WHERE feature_photos.photo_id = photos.id;

ALTER TABLE photos DROP COLUMN feature_id;
ALTER TABLE photos DROP COLUMN plant_observation_id;

ALTER TABLE accession_photos ADD COLUMN photo_id BIGINT REFERENCES photos (id);

-- Copy existing accession photos to the photos table. The photo IDs will be different, so we need
-- to temporarily record the mapping between the two.
--
-- GPS coordinates are copied in the next migration.
ALTER TABLE photos ADD COLUMN accession_photo_id BIGINT UNIQUE;

INSERT INTO photos (accession_photo_id, file_name, captured_time, created_time, modified_time,
                    content_type, size, gps_horiz_accuracy, storage_url)
SELECT accession_photos.id,
       filename,
       captured_time,
       uploaded_time,
       uploaded_time,
       content_type,
       size,
       gps_accuracy,
       CONCAT('file:///', a.facility_id, '/', SUBSTRING(a.number, 1, 1), '/',
              SUBSTRING(a.number, 2, 1), '/', SUBSTRING(a.number, 3, 1), '/', a.number, '/',
              filename)
FROM accession_photos
         JOIN accessions a ON accession_photos.accession_id = a.id;

UPDATE accession_photos
SET photo_id = (SELECT id FROM photos WHERE photos.accession_photo_id = accession_photos.id)
WHERE photo_id IS NULL;

ALTER TABLE photos DROP COLUMN accession_photo_id;

-- Photo ID is now the primary key; no need for the old ID.
ALTER TABLE accession_photos DROP COLUMN id;
ALTER TABLE accession_photos ADD CONSTRAINT accession_photos_pk PRIMARY KEY (photo_id);

-- No need for metadata that lives in the photos table.
ALTER TABLE accession_photos DROP COLUMN captured_time;
ALTER TABLE accession_photos DROP COLUMN content_type;
ALTER TABLE accession_photos DROP COLUMN filename;
ALTER TABLE accession_photos DROP COLUMN gps_accuracy;
ALTER TABLE accession_photos DROP COLUMN size;
ALTER TABLE accession_photos DROP COLUMN uploaded_time;
