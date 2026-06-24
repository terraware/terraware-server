-- Previous version is in V514__BiomassWaterDepth.sql
ALTER TABLE tracking.observation_biomass_details
    DROP CONSTRAINT mangrove_required_values;

ALTER TABLE tracking.observation_biomass_details
    ADD CONSTRAINT mangrove_required_values
        CHECK (
            (
                -- Terrestrial or mangroves with no water measurements
                water_depth_cm IS NULL
                AND salinity_ppt IS NULL
                AND ph IS NULL
                AND tide_id IS NULL
                AND tide_time IS NULL
            )
            OR (
                -- Mangrove: water measurements must all be set if any of them is set
                forest_type_id = 2
                AND water_depth_cm IS NOT NULL
                AND salinity_ppt IS NOT NULL
                AND ph IS NOT NULL
                AND tide_id IS NOT NULL
                AND tide_time IS NOT NULL
            )
        );
