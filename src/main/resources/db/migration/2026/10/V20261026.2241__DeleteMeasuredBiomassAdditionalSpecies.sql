DELETE FROM tracking.observation_biomass_additional_species AS additional
WHERE EXISTS (
    SELECT 1
    FROM tracking.recorded_trees AS trees
    WHERE trees.observation_id = additional.observation_id
    AND trees.monitoring_plot_id = additional.monitoring_plot_id
    AND trees.biomass_species_id = additional.biomass_species_id
)
OR EXISTS (
    SELECT 1
    FROM tracking.observation_biomass_quadrat_species AS quadrat_species
    WHERE quadrat_species.observation_id = additional.observation_id
    AND quadrat_species.monitoring_plot_id = additional.monitoring_plot_id
    AND quadrat_species.biomass_species_id = additional.biomass_species_id
);
