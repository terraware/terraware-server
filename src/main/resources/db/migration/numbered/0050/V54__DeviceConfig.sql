-- In the old device schema, settings were an arbitrary client-parsed string. Use a JSON object
-- instead so that down the road we can do more sophisticated validation on the server side.
ALTER TABLE devices DROP COLUMN settings;
ALTER TABLE devices ADD COLUMN settings JSONB;

ALTER TABLE devices ADD COLUMN parent_id BIGINT REFERENCES devices (id);

ALTER TABLE devices DROP COLUMN watchdog_seconds;
