ALTER TABLE tracking.observed_plot_species_totals
    ADD COLUMN biomass_species_id BIGINT,
    ADD FOREIGN KEY (biomass_species_id, observation_id)
        REFERENCES tracking.observation_biomass_species (id, observation_id) ON DELETE CASCADE;

CREATE UNIQUE INDEX ON tracking.observed_plot_species_totals
    (biomass_species_id, observation_id, monitoring_plot_history_id)
    WHERE biomass_species_id IS NOT NULL;

ALTER TABLE tracking.observed_substratum_species_totals
    ADD COLUMN biomass_species_id BIGINT,
    ADD FOREIGN KEY (biomass_species_id, observation_id)
        REFERENCES tracking.observation_biomass_species (id, observation_id) ON DELETE CASCADE;

CREATE UNIQUE INDEX ON tracking.observed_substratum_species_totals
    (biomass_species_id, observation_id, substratum_history_id)
    WHERE biomass_species_id IS NOT NULL;

ALTER TABLE tracking.observed_stratum_species_totals
    ADD COLUMN biomass_species_id BIGINT,
    ADD FOREIGN KEY (biomass_species_id, observation_id)
        REFERENCES tracking.observation_biomass_species (id, observation_id) ON DELETE CASCADE;

CREATE UNIQUE INDEX ON tracking.observed_stratum_species_totals
    (biomass_species_id, observation_id, stratum_history_id)
    WHERE biomass_species_id IS NOT NULL;

ALTER TABLE tracking.observed_site_species_totals
    ADD COLUMN biomass_species_id BIGINT,
    ADD FOREIGN KEY (biomass_species_id, observation_id)
        REFERENCES tracking.observation_biomass_species (id, observation_id) ON DELETE CASCADE;

CREATE UNIQUE INDEX ON tracking.observed_site_species_totals
    (biomass_species_id, observation_id, planting_site_history_id)
    WHERE biomass_species_id IS NOT NULL;
