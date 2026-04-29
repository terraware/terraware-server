CREATE TABLE tracking.observation_plot_results(
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    total_live INT NOT NULL,
    total_dead INT NOT NULL,
    total_existing INT NOT NULL,
    permanent_live INT NOT NULL,
    survival_rate INT,
    plant_density INT,

    PRIMARY KEY (observation_id, monitoring_plot_id),
    FOREIGN KEY (observation_id, monitoring_plot_id) REFERENCES tracking.observation_plots ON DELETE CASCADE
);

CREATE TABLE tracking.observation_substratum_results(
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    substratum_id BIGINT NOT NULL REFERENCES tracking.substrata ON DELETE CASCADE,
    total_live INT NOT NULL,
    total_dead INT NOT NULL,
    total_existing INT NOT NULL,
    permanent_live INT NOT NULL,
    survival_rate INT,
    survival_rate_std_dev INT,
    plant_density INT,
    plant_density_std_dev Int,

    PRIMARY KEY (observation_id, substratum_id)
);

CREATE TABLE tracking.observation_stratum_results(
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    stratum_id BIGINT NOT NULL REFERENCES tracking.strata ON DELETE CASCADE,
    total_live INT NOT NULL,
    total_dead INT NOT NULL,
    total_existing INT NOT NULL,
    permanent_live INT NOT NULL,
    survival_rate INT,
    survival_rate_std_dev INT,
    plant_density INT,
    plant_density_std_dev Int,

    PRIMARY KEY (observation_id, stratum_id)
);

CREATE TABLE tracking.observation_site_results(
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    total_live INT NOT NULL,
    total_dead INT NOT NULL,
    total_existing INT NOT NULL,
    permanent_live INT NOT NULL,
    survival_rate INT,
    survival_rate_std_dev INT,
    plant_density INT,
    plant_density_std_dev Int
);
