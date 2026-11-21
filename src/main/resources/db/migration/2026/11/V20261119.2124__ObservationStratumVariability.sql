CREATE TABLE tracking.observation_stratum_variability (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    stratum_history_id BIGINT NOT NULL REFERENCES tracking.stratum_histories ON DELETE CASCADE,
    num_plots INTEGER NOT NULL,
    measured_variability DOUBLE PRECISION,
    plausible_variability_low DOUBLE PRECISION,
    plausible_variability_high DOUBLE PRECISION,
    current_plot_count BIGINT,
    plot_count_low BIGINT,
    plot_count_best BIGINT,
    plot_count_high BIGINT,
    recommended_plot_count BIGINT,
    PRIMARY KEY (observation_id, stratum_history_id)
);

CREATE INDEX ON tracking.observation_stratum_variability (stratum_history_id);
