ALTER TABLE tracking.planting_zones ADD COLUMN boundary_modified_by BIGINT REFERENCES users;
ALTER TABLE tracking.planting_zones ADD COLUMN boundary_modified_time TIMESTAMP WITH TIME ZONE;

UPDATE tracking.planting_zones
SET boundary_modified_by = modified_by,
    boundary_modified_time = modified_time;

ALTER TABLE tracking.planting_zones ALTER COLUMN boundary_modified_by SET NOT NULL;
ALTER TABLE tracking.planting_zones ALTER COLUMN boundary_modified_time SET NOT NULL;
