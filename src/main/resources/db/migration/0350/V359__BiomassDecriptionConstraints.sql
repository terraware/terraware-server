UPDATE tracking.observation_biomass_details
SET description = LEFT(description, 25);

ALTER TABLE tracking.observation_biomass_details
ALTER COLUMN description TYPE VARCHAR(25);
