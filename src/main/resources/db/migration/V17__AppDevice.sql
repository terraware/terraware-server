ALTER TABLE app_device DROP COLUMN seed_collector_app_version;
ALTER TABLE app_device DROP COLUMN device_info;

ALTER TABLE app_device ADD COLUMN app_build TEXT;
ALTER TABLE app_device ADD COLUMN app_name TEXT;
ALTER TABLE app_device ADD COLUMN brand TEXT;
ALTER TABLE app_device ADD COLUMN created_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE app_device ADD COLUMN model TEXT;
ALTER TABLE app_device ADD COLUMN name TEXT;
ALTER TABLE app_device ADD COLUMN os_type TEXT;
ALTER TABLE app_device ADD COLUMN os_version TEXT;
ALTER TABLE app_device ADD COLUMN unique_id TEXT;

UPDATE app_device
SET created_time = '2021-01-01T00:00:00Z'
WHERE created_time IS NULL;
ALTER TABLE app_device ALTER COLUMN created_time SET NOT NULL;

ALTER TABLE app_device ADD CONSTRAINT app_device_unique
    UNIQUE (app_build, app_name, brand, model, name, os_type, os_version, unique_id)
