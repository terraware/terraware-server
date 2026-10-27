CREATE TABLE tracking.observation_biomass_additional_species (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    biomass_species_id BIGINT NOT NULL REFERENCES tracking.observation_biomass_species,

    FOREIGN KEY (observation_id, monitoring_plot_id)
        REFERENCES tracking.observation_biomass_details (observation_id, monitoring_plot_id)
        ON DELETE CASCADE,
    FOREIGN KEY (observation_id, monitoring_plot_id, biomass_species_id)
        REFERENCES tracking.observation_biomass_species (observation_id, monitoring_plot_id, id),

    PRIMARY KEY (observation_id, monitoring_plot_id, biomass_species_id)
);

CREATE INDEX ON tracking.observation_biomass_additional_species (monitoring_plot_id);
CREATE INDEX ON tracking.observation_biomass_additional_species (biomass_species_id);

INSERT INTO tracking.observation_biomass_additional_species
    (observation_id, monitoring_plot_id, biomass_species_id)
SELECT species.observation_id, species.monitoring_plot_id, species.id
FROM tracking.observation_biomass_species AS species
WHERE NOT EXISTS (
    SELECT 1
    FROM tracking.recorded_trees AS trees
    WHERE trees.biomass_species_id = species.id
)
AND NOT EXISTS (
    SELECT 1
    FROM tracking.observation_biomass_quadrat_species AS quadrat_species
    WHERE quadrat_species.biomass_species_id = species.id
);
