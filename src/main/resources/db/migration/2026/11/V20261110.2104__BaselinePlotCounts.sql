ALTER TABLE tracking.strata
    ADD COLUMN baseline_error_margin NUMERIC,
    ADD COLUMN baseline_num_temporary_plots INTEGER,
    ADD COLUMN baseline_students_t NUMERIC,
    ADD COLUMN baseline_variance NUMERIC;

UPDATE tracking.strata
    SET baseline_error_margin = plant_error_margin,
        baseline_num_temporary_plots = plant_num_permanent_plots + plant_num_temporary_plots,
        baseline_students_t = plant_students_t,
        baseline_variance = plant_variance;

ALTER TABLE tracking.strata
    ALTER COLUMN baseline_error_margin SET NOT NULL,
    ALTER COLUMN baseline_num_temporary_plots SET NOT NULL,
    ALTER COLUMN baseline_students_t SET NOT NULL,
    ALTER COLUMN baseline_variance SET NOT NULL;
