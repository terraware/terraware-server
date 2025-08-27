DROP TABLE tracking.t0_plot;

CREATE TABLE tracking.plot_t0_density
(
    monitoring_plot_id BIGINT  NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    species_id         BIGINT  NOT NULL REFERENCES species ON DELETE CASCADE,
    plot_density       NUMERIC NOT NULL,
    PRIMARY KEY (monitoring_plot_id, species_id)
);

CREATE TABLE tracking.plot_t0_observations
(
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    observation_id     BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    PRIMARY KEY (monitoring_plot_id),
    FOREIGN KEY (observation_id, monitoring_plot_id) REFERENCES tracking.observation_plots ON DELETE CASCADE
);
