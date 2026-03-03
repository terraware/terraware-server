ALTER TABLE tracking.observation_biomass_quadrat_species
    RENAME COLUMN abundance_percent TO abundance_count;
ALTER TABLE tracking.observation_biomass_quadrat_species
    DROP CONSTRAINT observation_biomass_quadrat_species_abundance_percent_check;

-- Count is 1/4 of percentage, so should be capped at 25 because that's 100%.
-- The UI constrains the value to 25, so data from actual users should be within
-- the required range, but we have early test data with values higher than 25.
UPDATE tracking.observation_biomass_quadrat_species
SET abundance_count = 25
WHERE abundance_count > 25;

ALTER TABLE tracking.observation_biomass_quadrat_species
    ADD CONSTRAINT abundance_count_check
        CHECK (abundance_count BETWEEN 0 AND 25);
