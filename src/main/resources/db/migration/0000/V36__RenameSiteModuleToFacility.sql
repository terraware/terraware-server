ALTER TABLE site_module_types RENAME TO facility_types;
ALTER TABLE site_modules RENAME TO facilities;

ALTER TABLE accessions RENAME COLUMN site_module_id TO facility_id;
ALTER TABLE collectors RENAME COLUMN site_module_id TO facility_id;
ALTER TABLE devices RENAME COLUMN site_module_id TO facility_id;
ALTER TABLE storage_locations RENAME COLUMN site_module_id TO facility_id;
