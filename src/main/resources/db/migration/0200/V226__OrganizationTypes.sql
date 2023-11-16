CREATE TABLE managed_location_types (
    id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE organization_types (
    id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE organization_managed_location_types (
  organization_id BIGINT NOT NULL REFERENCES organizations ON DELETE CASCADE,
  managed_location_type_id INTEGER NOT NULL REFERENCES managed_location_types,
  PRIMARY KEY (organization_id, managed_location_type_id)
);

ALTER TABLE organizations ADD COLUMN organization_type_id INTEGER REFERENCES organization_types;
ALTER TABLE organizations ADD COLUMN organization_type_details VARCHAR(100);
ALTER TABLE organizations ADD COLUMN website TEXT;

/* Require non-empty type details if type id is 'Other' (6), type details should be null otherwise. */
ALTER TABLE organizations ADD CONSTRAINT other_type_details
  CHECK (
    (organization_type_details IS NULL AND organization_type_id != 6)
      OR
    (organization_type_details IS NOT NULL AND organization_type_details <> '' AND organization_type_id = 6)
  );
