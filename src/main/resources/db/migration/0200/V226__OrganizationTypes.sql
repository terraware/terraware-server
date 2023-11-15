CREATE TABLE managed_facility_types (
    id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE organization_types (
    id INTEGER NOT NULL PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE organization_managed_facility_types (
  organization_id BIGINT NOT NULL REFERENCES organizations,
  managed_facility_type_id INTEGER NOT NULL REFERENCES managed_facility_types,
  UNIQUE (organization_id, managed_facility_type_id)
);

ALTER TABLE organizations ADD COLUMN type_id INTEGER REFERENCES organization_types;
ALTER TABLE organizations ADD COLUMN type_details VARCHAR(100);
ALTER TABLE organizations ADD COLUMN website TEXT;
