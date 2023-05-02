-- Needed in dev environments that have existing accessions and are running this migration and
-- the previous one at the same time.
INSERT INTO data_sources (id, name)
VALUES (1, 'Web'),
       (2, 'Seed Collector App')
ON CONFLICT (id) DO NOTHING;

UPDATE accessions
SET data_source_id = CASE WHEN app_device_id IS NULL THEN 1 ELSE 2 END
WHERE data_source_id IS NULL;

ALTER TABLE accessions DROP COLUMN app_device_id;
ALTER TABLE accessions ALTER COLUMN data_source_id SET NOT NULL;

DROP TABLE app_devices;
