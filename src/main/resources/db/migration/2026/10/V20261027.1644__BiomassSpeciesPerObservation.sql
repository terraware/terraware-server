-- Also drops the indexes and constraints that include the column.
ALTER TABLE tracking.observation_biomass_species DROP COLUMN monitoring_plot_id;

CREATE UNIQUE INDEX ON tracking.observation_biomass_species (observation_id, species_id)
    WHERE species_id IS NOT NULL;
CREATE UNIQUE INDEX ON tracking.observation_biomass_species (observation_id, scientific_name)
    WHERE scientific_name IS NOT NULL;
