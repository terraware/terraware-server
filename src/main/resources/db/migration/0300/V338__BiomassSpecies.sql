CREATE TABLE tracking.observation_biomass_species (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    species_id BIGINT REFERENCES species,
    scientific_name TEXT,
    common_name TEXT,
    is_invasive BOOLEAN NOT NULL,
    is_threatened BOOLEAN NOT NULL,

    CONSTRAINT species_identifier
        CHECK ((species_id IS NOT NULL AND scientific_name IS NULL)
            OR (species_id IS NULL AND scientific_name IS NOT NULL)),

    FOREIGN KEY (observation_id, monitoring_plot_id)
        REFERENCES tracking.observation_biomass_details (observation_id, monitoring_plot_id)
        ON DELETE CASCADE,

    UNIQUE (observation_id, monitoring_plot_id, species_id, scientific_name)
);

CREATE INDEX ON tracking.observation_biomass_species(observation_id);
CREATE INDEX ON tracking.observation_biomass_species(monitoring_plot_id);

-- This table is now redundant
DROP TABLE tracking.observation_biomass_additional_species;

ALTER TABLE tracking.observation_biomass_quadrat_species
    DROP CONSTRAINT species_identifier,
    DROP COLUMN is_invasive,
    DROP COLUMN is_threatened,
    ADD FOREIGN KEY (observation_id, monitoring_plot_id, species_id, species_name)
        REFERENCES tracking.observation_biomass_species(observation_id, monitoring_plot_id, species_id, scientific_name);

ALTER TABLE tracking.recorded_trees
    DROP CONSTRAINT species_identifier,
    ADD FOREIGN KEY (observation_id, monitoring_plot_id, species_id, species_name)
        REFERENCES tracking.observation_biomass_species(observation_id, monitoring_plot_id, species_id, scientific_name);
