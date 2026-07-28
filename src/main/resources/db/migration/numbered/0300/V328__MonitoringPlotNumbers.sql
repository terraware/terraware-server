ALTER TABLE tracking.monitoring_plots ADD COLUMN organization_id BIGINT REFERENCES organizations ON DELETE CASCADE;

UPDATE tracking.monitoring_plots mp
SET organization_id = (
    SELECT ps.organization_id
    FROM tracking.planting_sites ps
    WHERE ps.id = mp.planting_site_id
);

ALTER TABLE tracking.monitoring_plots ALTER COLUMN organization_id SET NOT NULL;

ALTER TABLE tracking.monitoring_plots ADD COLUMN plot_number BIGINT;

WITH plot_numbers AS (
    SELECT id,
           row_number() OVER (PARTITION BY organization_id ORDER BY id) AS plot_number
    FROM tracking.monitoring_plots
)
UPDATE tracking.monitoring_plots mp
SET plot_number = pn.plot_number
FROM plot_numbers AS pn
WHERE mp.id = pn.id;

ALTER TABLE tracking.monitoring_plots ALTER COLUMN plot_number SET NOT NULL;

ALTER TABLE tracking.monitoring_plots ADD UNIQUE (organization_id, plot_number);

INSERT INTO identifier_sequences (organization_id, prefix, next_value)
SELECT organization_id, 'PlotNumber', MAX(plot_number) + 1
FROM tracking.monitoring_plots
GROUP BY organization_id;
