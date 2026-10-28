ALTER TABLE tracking.recorded_trees
    DROP CONSTRAINT recorded_trees_observation_id_monitoring_plot_id_biomass_s_fkey;
ALTER TABLE tracking.observation_biomass_quadrat_species
    DROP CONSTRAINT observation_biomass_quadrat_s_observation_id_monitoring_pl_fkey;
ALTER TABLE tracking.observation_biomass_additional_species
    DROP CONSTRAINT observation_biomass_addition_observation_id_monitoring_pl_fkey1;

ALTER TABLE tracking.observation_biomass_species ADD UNIQUE (id, observation_id);

ALTER TABLE tracking.recorded_trees
    ADD FOREIGN KEY (observation_id, biomass_species_id)
        REFERENCES tracking.observation_biomass_species (observation_id, id);
ALTER TABLE tracking.observation_biomass_quadrat_species
    ADD FOREIGN KEY (observation_id, biomass_species_id)
        REFERENCES tracking.observation_biomass_species (observation_id, id);
ALTER TABLE tracking.observation_biomass_additional_species
    ADD FOREIGN KEY (observation_id, biomass_species_id)
        REFERENCES tracking.observation_biomass_species (observation_id, id);
