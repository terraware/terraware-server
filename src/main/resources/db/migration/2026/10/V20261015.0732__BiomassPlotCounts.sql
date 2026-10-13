ALTER TABLE tracking.strata
    ADD COLUMN biomass_error_margin NUMERIC,
    ADD COLUMN biomass_num_permanent_plots INTEGER,
    ADD COLUMN biomass_num_temporary_plots INTEGER,
    ADD COLUMN biomass_students_t NUMERIC,
    ADD COLUMN biomass_variance NUMERIC;

UPDATE tracking.strata
    SET biomass_error_margin = plant_error_margin,
        biomass_num_permanent_plots = plant_num_permanent_plots,
        biomass_num_temporary_plots = plant_num_temporary_plots,
        biomass_students_t = plant_students_t,
        biomass_variance = plant_variance;

ALTER TABLE tracking.strata
    ALTER COLUMN biomass_error_margin SET NOT NULL,
    ALTER COLUMN biomass_num_permanent_plots SET NOT NULL,
    ALTER COLUMN biomass_num_temporary_plots SET NOT NULL,
    ALTER COLUMN biomass_students_t SET NOT NULL,
    ALTER COLUMN biomass_variance SET NOT NULL;
