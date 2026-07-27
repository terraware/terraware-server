-- Previous version is in V333__ObservationPlotBiomassDetails.sql
ALTER TABLE tracking.observation_biomass_details
    DROP CONSTRAINT mangrove_required_values;

ALTER TABLE tracking.observation_biomass_details
    ADD CONSTRAINT mangrove_required_values
        CHECK (
            -- Terrestrial
            (forest_type_id = 1
                AND water_depth_cm IS NULL
                AND salinity_ppt IS NULL
                AND ph IS NULL
                AND tide_id IS NULL
                AND tide_time IS NULL) OR
            -- Mangrove
            (forest_type_id = 2
                AND salinity_ppt IS NOT NULL
                AND ph IS NOT NULL
                AND tide_id IS NOT NULL
                AND tide_time IS NOT NULL)
            );
