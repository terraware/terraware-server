CREATE TABLE tracking.observation_types (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

INSERT INTO tracking.observation_types (id, name)
VALUES (1, 'Monitoring'),
       (2, 'Biomass Measurements')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

ALTER TABLE tracking.observations ADD COLUMN is_ad_hoc BOOLEAN;

UPDATE tracking.observations
SET is_ad_hoc = false
WHERE is_ad_hoc IS NULL;

ALTER TABLE tracking.observations ALTER COLUMN is_ad_hoc SET NOT NULL;

ALTER TABLE tracking.observations ADD COLUMN observation_type_id INTEGER REFERENCES tracking.observation_types;

UPDATE tracking.observations
SET observation_type_id = 1
WHERE observation_type_id IS NULL;

ALTER TABLE tracking.observations ALTER COLUMN observation_type_id SET NOT NULL;

ALTER TABLE tracking.monitoring_plots ALTER COLUMN planting_subzone_id DROP NOT NULL;
ALTER TABLE tracking.observation_plots ALTER COLUMN monitoring_plot_history_id DROP NOT NULL;
