CREATE TABLE tracking.observed_plot_species_totals (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES species,
    total_live INTEGER NOT NULL DEFAULT 0,
    total_dead INTEGER NOT NULL DEFAULT 0,
    total_existing INTEGER NOT NULL DEFAULT 0,
    total_plants INTEGER NOT NULL DEFAULT 0,
    mortality_rate INTEGER NOT NULL DEFAULT 0,

    PRIMARY KEY (observation_id, monitoring_plot_id, species_id)
);

CREATE INDEX ON tracking.observed_plot_species_totals (observation_id);
CREATE INDEX ON tracking.observed_plot_species_totals (monitoring_plot_id);
CREATE INDEX ON tracking.observed_plot_species_totals (species_id);

CREATE TABLE tracking.observed_zone_species_totals (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    planting_zone_id BIGINT NOT NULL REFERENCES tracking.planting_zones ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES species,
    total_live INTEGER NOT NULL DEFAULT 0,
    total_dead INTEGER NOT NULL DEFAULT 0,
    total_existing INTEGER NOT NULL DEFAULT 0,
    total_plants INTEGER NOT NULL DEFAULT 0,
    mortality_rate INTEGER NOT NULL DEFAULT 0,

    PRIMARY KEY (observation_id, planting_zone_id, species_id)
);

CREATE INDEX ON tracking.observed_zone_species_totals (observation_id);
CREATE INDEX ON tracking.observed_zone_species_totals (planting_zone_id);
CREATE INDEX ON tracking.observed_zone_species_totals (species_id);

CREATE TABLE tracking.observed_site_species_totals (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    planting_site_id BIGINT NOT NULL REFERENCES tracking.planting_sites ON DELETE CASCADE,
    species_id BIGINT NOT NULL REFERENCES species,
    total_live INTEGER NOT NULL DEFAULT 0,
    total_dead INTEGER NOT NULL DEFAULT 0,
    total_existing INTEGER NOT NULL DEFAULT 0,
    total_plants INTEGER NOT NULL DEFAULT 0,
    mortality_rate INTEGER NOT NULL DEFAULT 0,

    PRIMARY KEY (observation_id, planting_site_id, species_id)
);

CREATE INDEX ON tracking.observed_site_species_totals (observation_id);
CREATE INDEX ON tracking.observed_site_species_totals (planting_site_id);
CREATE INDEX ON tracking.observed_site_species_totals (species_id);
