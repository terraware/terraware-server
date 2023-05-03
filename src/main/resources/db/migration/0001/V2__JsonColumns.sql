-- Use PostgreSQL's binary JSON format on tables that need arbitrary bags of JSON data.

ALTER TABLE app_device
    ADD COLUMN device_info jsonb;
