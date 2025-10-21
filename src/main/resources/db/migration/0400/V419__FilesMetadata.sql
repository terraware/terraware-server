ALTER TABLE files ADD COLUMN captured_local_time TIMESTAMP;
ALTER TABLE files ADD COLUMN geolocation GEOMETRY(Point);

UPDATE files
SET geolocation = amf.geolocation,
    captured_local_time = amf.captured_date
FROM accelerator.activity_media_files amf
WHERE amf.file_id = files.id;

UPDATE files
SET geolocation = op.gps_coordinates
FROM tracking.observation_photos op
WHERE op.file_id = files.id;

ALTER TABLE accelerator.activity_media_files DROP COLUMN captured_date;
ALTER TABLE accelerator.activity_media_files DROP COLUMN geolocation;
ALTER TABLE funder.published_activity_media_files DROP COLUMN captured_date;
ALTER TABLE funder.published_activity_media_files DROP COLUMN geolocation;
ALTER TABLE tracking.observation_photos DROP COLUMN gps_coordinates;
