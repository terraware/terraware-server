CREATE TABLE tracking.observation_plot_results(
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    monitoring_plot_history_id BIGINT NOT NULL REFERENCES tracking.monitoring_plot_histories ON DELETE CASCADE,
    total_live INT NOT NULL,
    total_dead INT NOT NULL,
    total_existing INT NOT NULL,
    permanent_live INT NOT NULL,
    survival_rate INT,
    plant_density INT,

    PRIMARY KEY (observation_id, monitoring_plot_id),
    FOREIGN KEY (observation_id, monitoring_plot_id) REFERENCES tracking.observation_plots ON DELETE CASCADE
);
CREATE INDEX ON tracking.observation_plot_results(monitoring_plot_history_id);
CREATE INDEX ON tracking.observation_plot_results(observation_id, monitoring_plot_history_id);

CREATE TABLE tracking.observation_substratum_results(
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    substratum_id BIGINT NOT NULL REFERENCES tracking.substrata ON DELETE CASCADE,
    substratum_history_id BIGINT NOT NULL REFERENCES tracking.substratum_histories ON DELETE CASCADE,
    total_live INT NOT NULL,
    total_dead INT NOT NULL,
    total_existing INT NOT NULL,
    permanent_live INT NOT NULL,
    survival_rate INT,
    survival_rate_std_dev INT,
    plant_density INT,
    plant_density_std_dev INT,

    PRIMARY KEY (observation_id, substratum_id)
);
CREATE INDEX ON tracking.observation_substratum_results(substratum_id);

CREATE TABLE tracking.observation_stratum_results(
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    stratum_id BIGINT NOT NULL REFERENCES tracking.strata ON DELETE CASCADE,
    stratum_history_id BIGINT REFERENCES tracking.stratum_histories ON DELETE CASCADE,
    total_live INT NOT NULL,
    total_dead INT NOT NULL,
    total_existing INT NOT NULL,
    permanent_live INT NOT NULL,
    survival_rate INT,
    survival_rate_std_dev INT,
    plant_density INT,
    plant_density_std_dev INT,

    PRIMARY KEY (observation_id, stratum_id)
);
CREATE INDEX ON tracking.observation_stratum_results(stratum_id);

CREATE TABLE tracking.observation_site_results(
    observation_id BIGINT PRIMARY KEY REFERENCES tracking.observations ON DELETE CASCADE,
    planting_site_id BIGINT NOT NULL REFERENCES tracking.planting_sites ON DELETE CASCADE,
    planting_site_history_id BIGINT NOT NULL REFERENCES tracking.planting_site_histories ON DELETE CASCADE,
    total_live INT NOT NULL,
    total_dead INT NOT NULL,
    total_existing INT NOT NULL,
    permanent_live INT NOT NULL,
    survival_rate INT,
    survival_rate_std_dev INT,
    plant_density INT,
    plant_density_std_dev Int
);
CREATE INDEX ON tracking.observation_site_results(planting_site_id);
