UPDATE tracking.planting_sites SET boundary = st_transform(boundary, 4326) WHERE boundary IS NOT NULL;
UPDATE tracking.planting_zones SET boundary = st_transform(boundary, 4326) WHERE boundary IS NOT NULL;
UPDATE tracking.planting_subzones SET boundary = st_transform(boundary, 4326) WHERE boundary IS NOT NULL;
UPDATE tracking.monitoring_plots SET boundary = st_transform(boundary, 4326) WHERE boundary IS NOT NULL;
