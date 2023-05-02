-- Balena's "uuid" on devices is not an actual well-formed UUID, just a random hex string of
-- variable length. So we can't use the database's UUID data type to store it.
ALTER TABLE device_managers RENAME COLUMN balena_uuid TO balena_uuid_old;
ALTER TABLE device_managers ADD COLUMN balena_uuid TEXT;
UPDATE device_managers SET balena_uuid = balena_uuid_old WHERE balena_uuid IS NULL;
ALTER TABLE device_managers DROP COLUMN balena_uuid_old;
ALTER TABLE device_managers ALTER COLUMN balena_uuid SET NOT NULL;

ALTER TABLE device_managers ADD CONSTRAINT device_managers_balena_id_unique UNIQUE (balena_id);
ALTER TABLE device_managers ADD CONSTRAINT device_managers_balena_uuid_unique UNIQUE (balena_uuid);
