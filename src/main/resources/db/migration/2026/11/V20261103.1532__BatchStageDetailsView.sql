CREATE TABLE nursery.batch_stages (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

INSERT INTO nursery.batch_stages (id, name)
VALUES (1, 'Germinating'), (2, 'Active Growth'), (3, 'Hardening Off'), (4, 'Ready');

CREATE VIEW nursery.batch_stage_details AS
SELECT id AS batch_id, stage_id, quantity, latest_observed_quantity
FROM nursery.batches
CROSS JOIN LATERAL (
    VALUES (1, germinating_quantity, latest_observed_germinating_quantity),
           (2, active_growth_quantity, latest_observed_active_growth_quantity),
           (3, hardening_off_quantity, latest_observed_hardening_off_quantity),
           (4, ready_quantity, latest_observed_ready_quantity)
) AS stages(stage_id, quantity, latest_observed_quantity);
