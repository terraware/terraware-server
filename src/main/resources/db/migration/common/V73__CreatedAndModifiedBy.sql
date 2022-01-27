-- Keep track of which user created and modified things. Since we don't have that information
-- anywhere, use the organization owner (or the user with the most privileged role) for existing
-- data.
--
-- Since we're going to backfill with the same user ID for all an organization's data, we propagate
-- it down the data hierarchy to keep the UPDATE statements simple, with a couple exceptions as
-- noted below.
ALTER TABLE organizations ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE organizations ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE organizations o SET (created_by, modified_by) = (
    SELECT ou.user_id, ou.user_id
    FROM organization_users ou
    WHERE ou.organization_id = o.id
    ORDER BY ou.role_id DESC
    LIMIT 1
);
ALTER TABLE organizations ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE organizations ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE organization_users ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE organization_users ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE organization_users SET (created_by, modified_by) = (
    SELECT organizations.created_by, organizations.modified_by
    FROM organizations
    WHERE organizations.id = organization_users.organization_id
);
ALTER TABLE organization_users ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE organization_users ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE projects ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE projects ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE projects SET (created_by, modified_by) = (
    SELECT organizations.created_by, organizations.modified_by
    FROM organizations
    WHERE organizations.id = projects.organization_id
);
ALTER TABLE projects ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE projects ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE sites ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE sites ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE sites SET (created_by, modified_by) = (
    SELECT projects.created_by, projects.modified_by
    FROM projects
    WHERE projects.id = sites.project_id
);
ALTER TABLE sites ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE sites ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE facilities ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE facilities ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE facilities SET (created_by, modified_by) = (
    SELECT sites.created_by, sites.modified_by
    FROM sites
    WHERE sites.id = facilities.site_id
);
ALTER TABLE facilities ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE facilities ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE species_names ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE species_names ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE species_names SET (created_by, modified_by) = (
    SELECT organizations.created_by, organizations.modified_by
    FROM organizations
    WHERE organizations.id = species_names.organization_id
);
ALTER TABLE species_names ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE species_names ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE species_options ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE species_options ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE species_options SET (created_by, modified_by) = (
    SELECT organizations.created_by, organizations.modified_by
    FROM organizations
    WHERE organizations.id = species_options.organization_id
);
ALTER TABLE species_options ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE species_options ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE project_users ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE project_users ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE project_users SET (created_by, modified_by) = (
    SELECT projects.created_by, projects.modified_by
    FROM projects
    WHERE projects.id = project_users.project_id
);
ALTER TABLE project_users ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE project_users ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE accessions ADD COLUMN modified_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW();
ALTER TABLE accessions ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE accessions ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE accessions SET (created_by, modified_by) = (
    SELECT facilities.created_by, facilities.modified_by
    FROM facilities
    WHERE facilities.id = accessions.facility_id
);
ALTER TABLE accessions ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE accessions ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE automations ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE automations ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE automations SET (created_by, modified_by) = (
    SELECT facilities.created_by, facilities.modified_by
    FROM facilities
    WHERE facilities.id = automations.facility_id
);
ALTER TABLE automations ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE automations ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE devices ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE devices ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE devices SET (created_by, modified_by) = (
    SELECT facilities.created_by, facilities.modified_by
    FROM facilities
    WHERE facilities.id = devices.facility_id
);
ALTER TABLE devices ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE devices ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE storage_locations ADD COLUMN created_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW();
ALTER TABLE storage_locations ADD COLUMN modified_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW();
ALTER TABLE storage_locations ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE storage_locations ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE storage_locations SET (created_by, modified_by) = (
    SELECT facilities.created_by, facilities.modified_by
    FROM facilities
    WHERE facilities.id = storage_locations.facility_id
);
ALTER TABLE storage_locations ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE storage_locations ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE timeseries ADD COLUMN created_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW();
ALTER TABLE timeseries ADD COLUMN modified_time TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW();
ALTER TABLE timeseries ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE timeseries ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE timeseries SET (created_by, modified_by) = (
    SELECT devices.created_by, devices.modified_by
    FROM devices
    WHERE devices.id = timeseries.device_id
);
ALTER TABLE timeseries ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE timeseries ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE layers ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE layers ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE layers SET (created_by, modified_by) = (
    SELECT sites.created_by, sites.modified_by
    FROM sites
    WHERE sites.id = layers.site_id
);
ALTER TABLE layers ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE layers ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE features ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE features ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE features SET (created_by, modified_by) = (
    SELECT layers.created_by, layers.modified_by
    FROM layers
    WHERE layers.id = features.layer_id
);
ALTER TABLE features ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE features ALTER COLUMN modified_by SET NOT NULL;

ALTER TABLE plant_observations ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE plant_observations ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE plant_observations SET (created_by, modified_by) = (
    SELECT features.created_by, features.modified_by
    FROM features
    WHERE features.id = plant_observations.feature_id
);
ALTER TABLE plant_observations ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE plant_observations ALTER COLUMN modified_by SET NOT NULL;

-- Photos are a special case: We already have a creator user ID that's populated in some cases.
-- Rename it so the column names are consistent across the schema, and backfill from the secondary
-- tables where it wasn't set; there should be either a feature_photos row or an accession_photos
-- row for each photo.
ALTER TABLE photos RENAME COLUMN user_id TO created_by;
ALTER TABLE photos ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE photos p SET created_by = (
    SELECT COALESCE(f.created_by, a.created_by)
    FROM photos p2
    LEFT JOIN feature_photos fp ON p2.id = fp.photo_id
    LEFT JOIN features f ON fp.feature_id = f.id
    LEFT JOIN accession_photos ap ON p2.id = ap.photo_id
    LEFT JOIN accessions a ON ap.accession_id = a.id
    WHERE p2.id = p.id
)
WHERE created_by IS NULL;
UPDATE photos SET modified_by = created_by WHERE modified_by IS NULL;
ALTER TABLE photos ALTER COLUMN modified_by SET NOT NULL;
