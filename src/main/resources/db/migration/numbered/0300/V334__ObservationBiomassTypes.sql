-- Percents rounded to integer
ALTER TABLE tracking.observation_biomass_details ALTER COLUMN herbaceous_cover_percent TYPE INTEGER;

-- CM rounded to integer
ALTER TABLE tracking.observation_biomass_details ALTER COLUMN water_depth_cm TYPE INTEGER;

-- Percents rounded to integer
ALTER TABLE tracking.observation_biomass_quadrat_species ALTER COLUMN abundance_percent TYPE INTEGER;

-- Shrub diameter is an integer
ALTER TABLE tracking.recorded_trees ALTER COLUMN shrub_diameter_cm TYPE INTEGER;
