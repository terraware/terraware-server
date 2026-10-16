ALTER TABLE tracking.recorded_trees
    DROP CONSTRAINT recorded_trees_biomass_species_id_fkey,
    DROP CONSTRAINT recorded_trees_observation_id_monitoring_plot_id_biomass_s_fkey,
    ADD CONSTRAINT recorded_trees_biomass_species_id_fkey
        FOREIGN KEY (biomass_species_id)
        REFERENCES tracking.observation_biomass_species (id),
    ADD CONSTRAINT recorded_trees_observation_id_monitoring_plot_id_biomass_s_fkey
        FOREIGN KEY (observation_id, monitoring_plot_id, biomass_species_id)
        REFERENCES tracking.observation_biomass_species (observation_id, monitoring_plot_id, id);

ALTER TABLE tracking.observation_biomass_quadrat_species
    DROP CONSTRAINT observation_biomass_quadrat_species_biomass_species_id_fkey,
    DROP CONSTRAINT observation_biomass_quadrat_s_observation_id_monitoring_pl_fkey,
    ADD CONSTRAINT observation_biomass_quadrat_species_biomass_species_id_fkey
        FOREIGN KEY (biomass_species_id)
        REFERENCES tracking.observation_biomass_species (id),
    ADD CONSTRAINT observation_biomass_quadrat_s_observation_id_monitoring_pl_fkey
        FOREIGN KEY (observation_id, monitoring_plot_id, biomass_species_id)
        REFERENCES tracking.observation_biomass_species (observation_id, monitoring_plot_id, id);
