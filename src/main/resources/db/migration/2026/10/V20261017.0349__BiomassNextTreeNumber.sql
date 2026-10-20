ALTER TABLE tracking.observation_biomass_details
    ADD COLUMN next_tree_number INTEGER NOT NULL DEFAULT 1;

UPDATE tracking.observation_biomass_details AS details
SET next_tree_number = tree_numbers.next_tree_number
FROM (
    SELECT observation_id, monitoring_plot_id, MAX(tree_number) + 1 AS next_tree_number
    FROM tracking.recorded_trees
    GROUP BY observation_id, monitoring_plot_id
) AS tree_numbers
WHERE details.observation_id = tree_numbers.observation_id
  AND details.monitoring_plot_id = tree_numbers.monitoring_plot_id;

ALTER TABLE tracking.recorded_trees
    DROP CONSTRAINT recorded_trees_observation_id_tree_number_trunk_number_key,
    ADD CONSTRAINT recorded_trees_observation_plot_tree_trunk_key
        UNIQUE (observation_id, monitoring_plot_id, tree_number, trunk_number);
