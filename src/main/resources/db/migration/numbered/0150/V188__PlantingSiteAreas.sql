ALTER TABLE tracking.planting_sites ADD COLUMN area_ha NUMERIC;
ALTER TABLE tracking.planting_zones ADD COLUMN area_ha NUMERIC;
ALTER TABLE tracking.planting_subzones ADD COLUMN area_ha NUMERIC;

-- Area must be calculated during shapefile import, so we don't want it to have a default,
-- but sites in existing test databases need to be made valid.
UPDATE tracking.planting_zones
SET area_ha = 1
WHERE area_ha IS NULL;

UPDATE tracking.planting_subzones
SET area_ha = 1
WHERE area_ha IS NULL;

ALTER TABLE tracking.planting_zones ALTER COLUMN area_ha SET NOT NULL;
ALTER TABLE tracking.planting_zones ALTER COLUMN boundary SET NOT NULL;

ALTER TABLE tracking.planting_subzones ALTER COLUMN area_ha SET NOT NULL;
ALTER TABLE tracking.planting_subzones ALTER COLUMN boundary SET NOT NULL;

ALTER TABLE tracking.planting_sites ADD CONSTRAINT area_positive CHECK (area_ha > 0);
ALTER TABLE tracking.planting_zones ADD CONSTRAINT area_positive CHECK (area_ha > 0);
ALTER TABLE tracking.planting_subzones ADD CONSTRAINT area_positive CHECK (area_ha > 0);
