ALTER TABLE tracking.planting_zones
    ADD COLUMN target_planting_density NUMERIC NOT NULL DEFAULT 1500;

ALTER TABLE tracking.planting_zones
    ADD CONSTRAINT positive_target_density CHECK (target_planting_density > 0);
