ALTER TABLE tracking.monitoring_plots ADD COLUMN size_meters INTEGER;

-- Monitoring plots are currently fixed-size.
UPDATE tracking.monitoring_plots
SET size_meters = 25
WHERE size_meters IS NULL;

ALTER TABLE tracking.monitoring_plots ALTER COLUMN size_meters SET NOT NULL;
