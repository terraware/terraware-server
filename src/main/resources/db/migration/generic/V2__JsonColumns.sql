-- When running on PostgreSQL, we use the native JSONB type for JSON columns. Add those columns
-- as text for other databases. Note that no database other than PostgreSQL is officially supported
-- by the project.

ALTER TABLE app_device
    ADD COLUMN device_info TEXT;
