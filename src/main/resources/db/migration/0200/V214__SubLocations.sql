ALTER TABLE seedbank.storage_locations SET SCHEMA public;
ALTER TABLE storage_locations RENAME TO sub_locations;

ALTER TABLE seedbank.accessions RENAME storage_location_id TO sub_location_id;
