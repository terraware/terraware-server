ALTER TABLE tracking.recorded_trees ADD COLUMN bole_height_m NUMERIC;

ALTER TABLE tracking.recorded_trees ADD CHECK (bole_height_m >= 0);

-- Previous version in V342. Bole height disallowed for shrubs.
ALTER TABLE tracking.recorded_trees
    DROP CONSTRAINT growth_form_specific_data,
    ADD CONSTRAINT growth_form_specific_data
        CHECK(
            (tree_growth_form_id = 1
                AND diameter_at_breast_height_cm IS NOT NULL
                AND point_of_measurement_m IS NOT NULL
                AND shrub_diameter_cm IS NULL
                AND trunk_number = 1) OR
            (tree_growth_form_id = 2
                AND bole_height_m IS NULL
                AND diameter_at_breast_height_cm IS NULL
                AND point_of_measurement_m IS NULL
                AND height_m IS NULL
                AND shrub_diameter_cm IS NOT NULL
                AND trunk_number = 1) OR
            (tree_growth_form_id = 3
                AND diameter_at_breast_height_cm IS NOT NULL
                AND point_of_measurement_m IS NOT NULL
                AND shrub_diameter_cm IS NULL)
            );
