-- Original data model had per-accession target humidities for refrigeration and freezing, but
-- the target humidity is really just an attribute of the storage condition. However, there is a
-- storage condition that is selected during processing that is separate from the one selected
-- when the seeds are actually stored.
ALTER TABLE accession
    ADD COLUMN target_storage_condition INTEGER REFERENCES storage_condition;
COMMENT ON COLUMN accession.target_storage_condition
    IS 'The intended storage condition of the accession as determined during initial processing.';

ALTER TABLE accession DROP COLUMN target_frozen_humidity;
ALTER TABLE accession DROP COLUMN target_refrigerated_humidity;

-- Processing state needs a "staff responsible" text field.
ALTER TABLE accession ADD COLUMN processing_staff_responsible TEXT;

-- Withdrawal destination is now a plain text field. CU-kgx97u
ALTER TABLE withdrawal ADD COLUMN destination TEXT;
ALTER TABLE withdrawal DROP COLUMN destination_id;
DROP TABLE withdrawal_destination;

-- Site information is now just a set of per-accession text fields.
ALTER TABLE accession ADD COLUMN collection_site_name TEXT;
ALTER TABLE accession ADD COLUMN collection_site_landowner TEXT;
ALTER TABLE accession ADD COLUMN collection_site_notes TEXT;
ALTER TABLE accession DROP COLUMN collection_site_location_id;
DROP TABLE collection_site_location;

-- Make enumerated values global, not per-site.
ALTER TABLE germination_seed_type DROP COLUMN site_module_id;
ALTER TABLE germination_substrate DROP COLUMN site_module_id;
ALTER TABLE germination_treatment DROP COLUMN site_module_id;
ALTER TABLE withdrawal_purpose DROP COLUMN site_module_id;

-- Use the column name "name" for the names of enumerated values everywhere.
ALTER TABLE germination_seed_type RENAME seed_type TO name;
ALTER TABLE germination_substrate RENAME substrate TO name;
ALTER TABLE germination_treatment RENAME treatment TO name;
ALTER TABLE withdrawal_purpose RENAME purpose TO name;
