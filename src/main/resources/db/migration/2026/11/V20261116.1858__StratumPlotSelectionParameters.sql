CREATE TABLE tracking.stratum_plot_selection_parameters (
    stratum_id BIGINT NOT NULL REFERENCES tracking.strata ON DELETE CASCADE,
    observation_type_id INTEGER NOT NULL REFERENCES tracking.observation_types,
    variance NUMERIC NOT NULL,
    students_t NUMERIC NOT NULL,
    error_margin NUMERIC NOT NULL,
    num_permanent_plots INTEGER NOT NULL,
    num_temporary_plots INTEGER NOT NULL,
    PRIMARY KEY (stratum_id, observation_type_id),
    CHECK (observation_type_id <> 3 OR num_permanent_plots = 0),
    CONSTRAINT must_have_permanent_plots CHECK (observation_type_id <> 1 OR num_permanent_plots > 0),
    CONSTRAINT no_negative_temporary_plots CHECK (observation_type_id <> 1 OR num_temporary_plots >= 0)
);

CREATE INDEX ON tracking.stratum_plot_selection_parameters (observation_type_id);

INSERT INTO tracking.stratum_plot_selection_parameters
    (stratum_id, observation_type_id, variance, students_t, error_margin,
     num_permanent_plots, num_temporary_plots)
SELECT s.id, counts.*
FROM tracking.strata s
CROSS JOIN LATERAL (
    VALUES (1, s.plant_variance, s.plant_students_t, s.plant_error_margin,
            s.plant_num_permanent_plots, s.plant_num_temporary_plots),
           (2, s.biomass_variance, s.biomass_students_t, s.biomass_error_margin,
            s.biomass_num_permanent_plots, s.biomass_num_temporary_plots),
           (3, s.baseline_variance, s.baseline_students_t, s.baseline_error_margin,
            0, s.baseline_num_temporary_plots)
) AS counts(observation_type_id, variance, students_t, error_margin,
            num_permanent_plots, num_temporary_plots);
