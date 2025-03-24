UPDATE observation_biomass_details
SET description = LEFT(description, 25);

ALTER TABLE observation_biomass_details
ADD CONSTRAINT description_length_check
    CHECK (LENGTH(description) <= 25);
