ALTER TABLE seedbank.storage_locations ADD UNIQUE (facility_id, name);
ALTER TABLE seedbank.storage_locations DROP COLUMN enabled;
