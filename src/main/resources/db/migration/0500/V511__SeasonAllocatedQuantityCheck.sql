ALTER TABLE tracking.planting_season_allocated_species
    DROP CONSTRAINT planting_season_allocated_species_quantity_check;
ALTER TABLE tracking.planting_season_allocated_species
    ADD CONSTRAINT planting_season_allocated_species_quantity_check CHECK (quantity >= 0);
