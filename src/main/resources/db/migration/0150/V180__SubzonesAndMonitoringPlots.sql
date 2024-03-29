-- Rename the `tracking.plots` table to `tracking.planting_subzones`.
ALTER TABLE tracking.plots RENAME TO planting_subzones;

-- Rename `plot_id` reference from `tracking.plantings` to reference a `planting_subzone`.
ALTER TABLE tracking.plantings RENAME plot_id TO planting_subzone_id;
ALTER TABLE tracking.plantings
    RENAME CONSTRAINT plantings_plot_id_fkey TO plantings_planting_subzone_id_fkey;

-- Drop the `tracking.plot_populations` view. Recreate with name,
-- `tracking.planting_subzone_populations`.
DROP VIEW IF EXISTS tracking.plot_populations;
CREATE VIEW tracking.planting_subzone_populations AS
    SELECT planting_site_id, planting_subzone_id, species_id, SUM(num_plants) AS total_plants
    FROM tracking.plantings
    WHERE planting_subzone_id IS NOT NULL
    GROUP BY planting_site_id, planting_subzone_id, species_id;

-- Rename `plot_names` column in `nursery.withdrawal_summaries` view to `planting_subzone_names`.
ALTER VIEW nursery.withdrawal_summaries RENAME plot_names TO planting_subzone_names;

-- Rename `num_plots` column in `tracking.planting_site_summaries` to `numPlantingSubzones`.
ALTER VIEW tracking.planting_site_summaries RENAME num_plots TO num_planting_subzones;

-- Add a `tracking.monitoring_plots` table which references an id from `tracking.planting_subzones`.
CREATE TABLE tracking.monitoring_plots (
    id BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    planting_subzone_id BIGINT NOT NULL REFERENCES tracking.planting_subzones ON DELETE CASCADE,
    name TEXT NOT NULL,
    full_name TEXT NOT NULL,
    boundary GEOMETRY(MULTIPOLYGON),
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    modified_by BIGINT NOT NULL REFERENCES users,
    modified_time TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (planting_subzone_id, name)
);
