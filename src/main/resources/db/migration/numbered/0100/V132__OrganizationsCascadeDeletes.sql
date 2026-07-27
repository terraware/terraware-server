-- The foreign-key constraints on various descendants of the organizations table didn't support
-- cascading deletes because originally we didn't allow deleting organizations (or rather, we
-- didn't actually delete the underlying data, just made it inaccessible). Now we're adding
-- support for true deletion, so switch to cascading deletion.
--
-- We don't cascade to tables that have references to external resources, e.g., URLs of photos on
-- file stores. Those need to be deleted by application code that can also remove the external
-- resources. We also don't cascade deletes from reference tables since deleting an enum value
-- that's still in use is a code bug.
--
-- A few of the constraints we're replacing here were created prior to some changes in our table
-- names; create them with names that follow the current naming conventions.

ALTER TABLE accessions DROP CONSTRAINT accession_site_module_id_fkey;
ALTER TABLE accessions ADD CONSTRAINT accessions_facility_id_fkey
    FOREIGN KEY (facility_id) REFERENCES facilities (id) ON DELETE CASCADE;

ALTER TABLE automations DROP CONSTRAINT automations_device_id_fkey;
ALTER TABLE automations ADD CONSTRAINT automations_device_id_fkey
    FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE CASCADE;

ALTER TABLE automations DROP CONSTRAINT automations_facility_id_fkey;
ALTER TABLE automations ADD CONSTRAINT automations_facility_id_fkey
    FOREIGN KEY (facility_id) REFERENCES facilities (id) ON DELETE CASCADE;

ALTER TABLE devices DROP CONSTRAINT device_site_module_id_fkey;
ALTER TABLE devices ADD CONSTRAINT devices_facility_id_fkey
    FOREIGN KEY (facility_id) REFERENCES facilities (id) ON DELETE CASCADE;

ALTER TABLE facilities DROP CONSTRAINT facilities_organization_id_fkey;
ALTER TABLE facilities ADD CONSTRAINT facilities_organization_id_fkey
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE;

ALTER TABLE notifications DROP CONSTRAINT notifications_organization_id_fkey;
ALTER TABLE notifications ADD CONSTRAINT notifications_organization_id_fkey
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE;

ALTER TABLE organization_users DROP CONSTRAINT organization_users_organization_id_fkey;
ALTER TABLE organization_users ADD CONSTRAINT organization_users_organization_id_fkey
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE;

ALTER TABLE species DROP CONSTRAINT species_organization_id_fkey;
ALTER TABLE species ADD CONSTRAINT species_organization_id_fkey
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE;

ALTER TABLE species_problems DROP CONSTRAINT species_problems_species_id_fkey;
ALTER TABLE species_problems ADD CONSTRAINT species_problems_species_id_fkey
    FOREIGN KEY (species_id) REFERENCES species (id) ON DELETE CASCADE;

ALTER TABLE storage_locations DROP CONSTRAINT storage_location_site_module_id_fkey;
ALTER TABLE storage_locations ADD CONSTRAINT storage_locations_facility_id_fkey
    FOREIGN KEY (facility_id) REFERENCES facilities (id) ON DELETE CASCADE;

ALTER TABLE timeseries DROP CONSTRAINT timeseries_device_id_fkey;
ALTER TABLE timeseries ADD CONSTRAINT timeseries_device_id_fkey
    FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE CASCADE;

-- Adding a constraint holds an exclusive lock on the table. Most of our tables are currently small
-- enough that the lock isn't held for long enough to cause problems. But this is a large table and
-- validating the foreign keys on the existing rows is not a quick operation.
--
-- Mark the constraint as NOT VALID here, and then validate it in a separate migration. A NOT VALID
-- constraint still does what we want: it is checked on insert and update, and deletes get cascaded
-- correctly.
ALTER TABLE timeseries_values DROP CONSTRAINT timeseries_value_timeseries_id_fkey;
ALTER TABLE timeseries_values ADD CONSTRAINT timeseries_values_timeseries_id_fkey
    FOREIGN KEY (timeseries_id) REFERENCES timeseries (id) ON DELETE CASCADE NOT VALID;

ALTER TABLE upload_problems DROP CONSTRAINT upload_problems_upload_id_fkey;
ALTER TABLE upload_problems ADD CONSTRAINT upload_problems_upload_id_fkey
    FOREIGN KEY (upload_id) REFERENCES uploads (id) ON DELETE CASCADE;

ALTER TABLE user_preferences DROP CONSTRAINT user_preferences_organization_id_fkey;
ALTER TABLE user_preferences ADD CONSTRAINT user_preferences_organization_id_fkey
    FOREIGN KEY (organization_id) REFERENCES organizations (id) ON DELETE CASCADE;
