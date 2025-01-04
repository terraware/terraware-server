CREATE TABLE tracking.biomass_forest_types (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

-- This is also in R__TypeCodes.sql, but is repeated here to ensure migration is successful
INSERT INTO tracking.biomass_forest_types (id, name)
VALUES (1, 'Terrestrial'),
       (2, 'Mangrove')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

CREATE TABLE tracking.mangrove_tides(
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

-- This is also in R__TypeCodes.sql, but is repeated here to ensure migration is successful
INSERT INTO tracking.mangrove_tides (id, name)
VALUES (1, 'High'),
       (2, 'Low')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

CREATE TABLE tracking.observation_biomass_details (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    description TEXT,
    forest_type_id INTEGER NOT NULL REFERENCES tracking.biomass_forest_types,
    small_trees_count_low INTEGER NOT NULL CHECK ( 0 <= small_trees_count_low),
    small_trees_count_high INTEGER NOT NULL CHECK ( small_trees_count_low <= small_trees_count_high),
    herbaceous_cover_percent FLOAT NOT NULL CHECK ( 0 <= herbaceous_cover_percent AND herbaceous_cover_percent <= 100),
    soil_assessment TEXT NOT NULL,
    water_depth_cm FLOAT CHECK ( 0 <= water_depth_cm),
    salinity_ppt FLOAT CHECK ( 0 <= salinity_ppt),
    ph FLOAT CHECK ( 0 <= ph AND ph <= 14),
    tide_id INTEGER REFERENCES tracking.mangrove_tides,
    tide_time TIMESTAMP WITH TIME ZONE,

    CONSTRAINT mangrove_required_values
        CHECK (
            (forest_type_id = 1
                 AND water_depth_cm IS NULL
                 AND salinity_ppt IS NULL
                 AND ph IS NULL
                 AND tide_id IS NULL
                 AND tide_time IS NULL) OR
            (forest_type_id = 2
                AND water_depth_cm IS NOT NULL
                AND salinity_ppt IS NOT NULL
                AND ph IS NOT NULL
                AND tide_id IS NOT NULL
                AND tide_time IS NOT NULL)
            ),

    PRIMARY KEY (observation_id, monitoring_plot_id),
    FOREIGN KEY (observation_id, monitoring_plot_id)
        REFERENCES tracking.observation_plots (observation_id, monitoring_plot_id)
);

CREATE INDEX ON tracking.observation_biomass_details(observation_id);
CREATE INDEX ON tracking.observation_biomass_details(monitoring_plot_id);

CREATE TABLE tracking.observation_biomass_quadrant_details (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    position_id INTEGER NOT NULL REFERENCES tracking.observation_plot_positions,
    description TEXT,

    PRIMARY KEY (observation_id, monitoring_plot_id, position_id),
    FOREIGN KEY (observation_id, monitoring_plot_id)
        REFERENCES tracking.observation_biomass_details (observation_id, monitoring_plot_id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX ON tracking.observation_biomass_quadrant_details
    (observation_id, monitoring_plot_id, position_id);

CREATE INDEX ON tracking.observation_biomass_quadrant_details(observation_id);
CREATE INDEX ON tracking.observation_biomass_quadrant_details(monitoring_plot_id);

CREATE TABLE tracking.observation_biomass_quadrant_species (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    position_id INTEGER NOT NULL REFERENCES tracking.observation_plot_positions,
    species_id BIGINT REFERENCES species,
    species_name TEXT,
    is_invasive BOOLEAN NOT NULL,
    is_threatened BOOLEAN NOT NULL,
    abundance_percent FLOAT NOT NULL CHECK ( 0 <= abundance_percent AND abundance_percent <= 100),

    CONSTRAINT species_identifier
        CHECK ((species_id IS NOT NULL AND species_name IS NULL)
                OR (species_id IS NULL AND species_name IS NOT NULL)),

    FOREIGN KEY (observation_id, monitoring_plot_id)
        REFERENCES tracking.observation_biomass_details (observation_id, monitoring_plot_id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX ON tracking.observation_biomass_quadrant_species
    (observation_id, monitoring_plot_id, position_id, species_id)
    WHERE species_id IS NOT NULL;

CREATE UNIQUE INDEX ON tracking.observation_biomass_quadrant_species
    (observation_id, monitoring_plot_id, position_id, species_name)
    WHERE species_name IS NOT NULL;

CREATE INDEX ON tracking.observation_biomass_quadrant_species(observation_id);
CREATE INDEX ON tracking.observation_biomass_quadrant_species(monitoring_plot_id);

CREATE TABLE tracking.observation_biomass_additional_species (
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    species_id BIGINT REFERENCES species,
    species_name TEXT,
    is_invasive BOOLEAN NOT NULL,
    is_threatened BOOLEAN NOT NULL,

    CONSTRAINT species_identifier
        CHECK ((species_id IS NOT NULL AND species_name IS NULL)
                OR (species_id IS NULL AND species_name IS NOT NULL)),

    CONSTRAINT invasive_or_threatened
        CHECK ((is_invasive IS true OR is_threatened IS true)),

    FOREIGN KEY (observation_id, monitoring_plot_id)
        REFERENCES tracking.observation_biomass_details (observation_id, monitoring_plot_id)
        ON DELETE CASCADE
);

CREATE UNIQUE INDEX ON tracking.observation_biomass_additional_species
    (observation_id, monitoring_plot_id, species_id)
    WHERE species_id IS NOT NULL;

CREATE UNIQUE INDEX ON tracking.observation_biomass_additional_species
    (observation_id, monitoring_plot_id, species_name)
    WHERE species_name IS NOT NULL;

CREATE INDEX ON tracking.observation_biomass_additional_species(observation_id);
CREATE INDEX ON tracking.observation_biomass_additional_species(monitoring_plot_id);

CREATE TABLE tracking.tree_growth_forms(
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

-- This is also in R__TypeCodes.sql, but is repeated here to ensure migration is successful
INSERT INTO tracking.tree_growth_forms (id, name)
VALUES (1, 'Trees'),
       (2, 'Shrubs')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

CREATE TABLE tracking.recorded_trees (
    id BIGINT PRIMARY KEY,
    observation_id BIGINT NOT NULL REFERENCES tracking.observations ON DELETE CASCADE,
    monitoring_plot_id BIGINT NOT NULL REFERENCES tracking.monitoring_plots ON DELETE CASCADE,
    species_id BIGINT REFERENCES species,
    species_name TEXT,
    tree_number BIGINT NOT NULL CHECK ( 1 <= tree_number ),
    tree_growth_form_id INTEGER NOT NULL REFERENCES tracking.tree_growth_forms,
    is_dead BOOLEAN NOT NULL,
    is_trunk BOOLEAN,
    diameter_at_breast_height_cm FLOAT CHECK ( 0 <= diameter_at_breast_height_cm ),
    point_of_measurement_m FLOAT CHECK ( 0 <= point_of_measurement_m ),
    height_m FLOAT CHECK ( 0 <= height_m ),
    shrub_diameter_cm FLOAT CHECK ( 0 <= shrub_diameter_cm ),
    description TEXT,

    CONSTRAINT species_identifier
     CHECK ((species_id IS NOT NULL AND species_name IS NULL)
         OR (species_id IS NULL AND species_name IS NOT NULL)),

    CONSTRAINT growth_form_specific_data
        CHECK (
            (tree_growth_form_id = 1
                AND is_trunk IS NOT NULL
                AND diameter_at_breast_height_cm IS NOT NULL
                AND point_of_measurement_m IS NOT NULL
                AND shrub_diameter_cm IS NULL) OR
            (tree_growth_form_id = 2
                AND is_trunk IS NULL
                AND diameter_at_breast_height_cm IS NULL
                AND point_of_measurement_m IS NULL
                AND height_m IS NULL
                AND shrub_diameter_cm IS NOT NULL)
            ),

    CONSTRAINT height_required_dbm_threshold
        CHECK ( NOT (diameter_at_breast_height_cm > 5 AND height_m IS NULL)),

    FOREIGN KEY (observation_id, monitoring_plot_id)
        REFERENCES tracking.observation_biomass_details (observation_id, monitoring_plot_id)
        ON DELETE CASCADE,

    UNIQUE (observation_id, tree_number)
);

CREATE INDEX ON tracking.recorded_trees(observation_id);
CREATE INDEX ON tracking.recorded_trees(monitoring_plot_id);

CREATE TABLE tracking.recorded_branches (
    id BIGINT PRIMARY KEY,
    tree_id BIGINT NOT NULL REFERENCES tracking.recorded_trees ON DELETE CASCADE,
    branch_number BIGINT NOT NULL CHECK ( 1 <= branch_number ),
    diameter_at_breast_height_cm FLOAT NOT NULL CHECK ( 0 <= diameter_at_breast_height_cm ),
    point_of_measurement_m FLOAT NOT NULL CHECK ( 0 <= point_of_measurement_m ),
    is_dead BOOLEAN NOT NULL,
    description TEXT,

    UNIQUE (tree_id, branch_number)
);

CREATE INDEX ON tracking.recorded_branches(tree_id);
