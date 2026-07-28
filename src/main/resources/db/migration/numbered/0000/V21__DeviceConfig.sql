-- Use the device table as the list of hardware devices monitored by the device manager (the "site"
-- container in Balena).

ALTER TABLE device ADD COLUMN device_type TEXT;
ALTER TABLE device ADD COLUMN make TEXT;
ALTER TABLE device ADD COLUMN model TEXT;
ALTER TABLE device ADD COLUMN protocol TEXT;
ALTER TABLE device ADD COLUMN address TEXT;
ALTER TABLE device ADD COLUMN port INTEGER;
ALTER TABLE device ADD COLUMN settings TEXT;
ALTER TABLE device ADD COLUMN polling_interval INTEGER;

-- No advantage to having a separate table of device types since it's opaque to the server anyway
-- and is only used by the device manager; make it a text field instead.
ALTER TABLE device DROP COLUMN device_type_id;

DROP TABLE device_type;

UPDATE device SET device_type = 'Unknown' WHERE device_type IS NULL;
UPDATE device SET make = 'Unknown' WHERE make IS NULL;
UPDATE device SET model = 'Unknown' WHERE model IS NULL;

ALTER TABLE device ALTER COLUMN device_type SET NOT NULL;
ALTER TABLE device ALTER COLUMN make SET NOT NULL;
ALTER TABLE device ALTER COLUMN model SET NOT NULL;
