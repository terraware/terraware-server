CREATE TABLE time_zones (
    time_zone TEXT PRIMARY KEY
);

ALTER TABLE facilities
    ADD COLUMN time_zone TEXT REFERENCES time_zones ON DELETE SET NULL;
ALTER TABLE organizations
    ADD COLUMN time_zone TEXT REFERENCES time_zones ON DELETE SET NULL;
ALTER TABLE tracking.planting_sites
    ADD COLUMN time_zone TEXT REFERENCES time_zones ON DELETE SET NULL;
ALTER TABLE users
    ADD COLUMN time_zone TEXT REFERENCES time_zones ON DELETE SET NULL;
