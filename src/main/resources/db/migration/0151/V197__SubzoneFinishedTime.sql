ALTER TABLE tracking.planting_subzones ADD COLUMN finished_planting_time TIMESTAMP WITH TIME ZONE;
UPDATE tracking.planting_subzones SET finished_planting_time = NOW() WHERE finished_planting;
ALTER TABLE tracking.planting_subzones DROP COLUMN finished_planting;
