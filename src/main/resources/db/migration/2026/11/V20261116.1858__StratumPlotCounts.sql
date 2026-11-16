CREATE TABLE tracking.stratum_plot_counts (
    stratum_id BIGINT NOT NULL REFERENCES tracking.strata ON DELETE CASCADE,
    observation_type_id INTEGER NOT NULL REFERENCES tracking.observation_types,
    variance NUMERIC NOT NULL,
    students_t NUMERIC NOT NULL,
    error_margin NUMERIC NOT NULL,
    num_permanent_plots INTEGER,
    num_temporary_plots INTEGER NOT NULL,
    PRIMARY KEY (stratum_id, observation_type_id),
    CHECK ((observation_type_id = 3) = (num_permanent_plots IS NULL)),
    CONSTRAINT must_have_permanent_clusters CHECK (observation_type_id <> 1 OR num_permanent_plots > 0),
    CONSTRAINT no_negative_temporary_plots CHECK (observation_type_id <> 1 OR num_temporary_plots >= 0)
);

CREATE INDEX ON tracking.stratum_plot_counts (observation_type_id);

LOCK TABLE tracking.strata IN SHARE ROW EXCLUSIVE MODE;

INSERT INTO tracking.stratum_plot_counts
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
            NULL, s.baseline_num_temporary_plots)
) AS counts(observation_type_id, variance, students_t, error_margin,
            num_permanent_plots, num_temporary_plots);

CREATE FUNCTION tracking.sync_stratum_plot_counts() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO tracking.stratum_plot_counts
        (stratum_id, observation_type_id, variance, students_t, error_margin,
         num_permanent_plots, num_temporary_plots)
    VALUES (NEW.id, 1, NEW.plant_variance, NEW.plant_students_t, NEW.plant_error_margin,
            NEW.plant_num_permanent_plots, NEW.plant_num_temporary_plots),
           (NEW.id, 2, NEW.biomass_variance, NEW.biomass_students_t, NEW.biomass_error_margin,
            NEW.biomass_num_permanent_plots, NEW.biomass_num_temporary_plots),
           (NEW.id, 3, NEW.baseline_variance, NEW.baseline_students_t, NEW.baseline_error_margin,
            NULL, NEW.baseline_num_temporary_plots)
    ON CONFLICT (stratum_id, observation_type_id) DO UPDATE
    SET variance = excluded.variance,
        students_t = excluded.students_t,
        error_margin = excluded.error_margin,
        num_permanent_plots = excluded.num_permanent_plots,
        num_temporary_plots = excluded.num_temporary_plots;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER sync_stratum_plot_counts
    AFTER INSERT OR UPDATE ON tracking.strata
    FOR EACH ROW EXECUTE FUNCTION tracking.sync_stratum_plot_counts();
