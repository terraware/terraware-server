ALTER TABLE tracking.planting_zones ADD COLUMN stable_id TEXT;
ALTER TABLE tracking.planting_subzones ADD COLUMN stable_id TEXT;
ALTER TABLE tracking.planting_zone_histories ADD COLUMN stable_id TEXT;
ALTER TABLE tracking.planting_subzone_histories ADD COLUMN stable_id TEXT;

UPDATE tracking.planting_zones SET stable_id = name;
UPDATE tracking.planting_subzones SET stable_id = full_name;
UPDATE tracking.planting_zone_histories SET stable_id = name;
UPDATE tracking.planting_subzone_histories SET stable_id = full_name;

ALTER TABLE tracking.planting_zones ALTER COLUMN stable_id SET NOT NULL;
ALTER TABLE tracking.planting_subzones ALTER COLUMN stable_id SET NOT NULL;
ALTER TABLE tracking.planting_zone_histories ALTER COLUMN stable_id SET NOT NULL;
ALTER TABLE tracking.planting_subzone_histories ALTER COLUMN stable_id SET NOT NULL;

ALTER TABLE tracking.planting_zones ADD UNIQUE (planting_site_id, stable_id);
ALTER TABLE tracking.planting_subzones ADD UNIQUE (planting_site_id, stable_id);
