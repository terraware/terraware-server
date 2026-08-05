ALTER TABLE tracking.strata ADD COLUMN target_plant_density NUMERIC;
ALTER TABLE tracking.strata ADD CONSTRAINT target_plant_density_positive
    CHECK (target_plant_density > 0);
