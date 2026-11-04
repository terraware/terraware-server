DROP VIEW nursery.batch_stage_details;

CREATE TABLE nursery.batch_stage_details (
    batch_id                 BIGINT NOT NULL REFERENCES nursery.batches ON DELETE CASCADE,
    stage_id                 INTEGER NOT NULL REFERENCES nursery.batch_stages ON DELETE CASCADE,
    quantity                 INTEGER NOT NULL CHECK (quantity >= 0),
    latest_observed_quantity INTEGER NOT NULL CHECK (latest_observed_quantity >= 0),
    PRIMARY KEY (batch_id, stage_id)
);

CREATE INDEX ON nursery.batch_stage_details (stage_id);

INSERT INTO nursery.batch_stage_details (batch_id, stage_id, quantity, latest_observed_quantity)
SELECT id, stage_id, quantity, latest_observed_quantity
FROM nursery.batches
CROSS JOIN LATERAL (
    VALUES (1, germinating_quantity, latest_observed_germinating_quantity),
           (2, active_growth_quantity, latest_observed_active_growth_quantity),
           (3, hardening_off_quantity, latest_observed_hardening_off_quantity),
           (4, ready_quantity, latest_observed_ready_quantity)
) AS stages(stage_id, quantity, latest_observed_quantity);

CREATE OR REPLACE VIEW nursery.facility_inventories AS
SELECT b.organization_id, b.species_id, b.facility_id,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id = 4), 0) AS ready_quantity,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id = 2), 0) AS active_growth_quantity,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id = 1), 0) AS germinating_quantity,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id = 3), 0) AS hardening_off_quantity,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id <> 1), 0) AS total_quantity
FROM nursery.batches b JOIN nursery.batch_stage_details q ON q.batch_id = b.id
GROUP BY b.organization_id, b.species_id, b.facility_id
HAVING SUM(q.quantity) > 0;

CREATE OR REPLACE VIEW nursery.facility_inventory_totals AS
SELECT b.organization_id, b.facility_id,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id = 4), 0) AS ready_quantity,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id = 2), 0) AS active_growth_quantity,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id = 1), 0) AS germinating_quantity,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id = 3), 0) AS hardening_off_quantity,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id <> 1), 0) AS total_quantity,
       COUNT(DISTINCT b.species_id) AS total_species
FROM nursery.batches b JOIN nursery.batch_stage_details q ON q.batch_id = b.id
GROUP BY b.organization_id, b.facility_id;

CREATE OR REPLACE VIEW nursery.inventories AS
SELECT b.organization_id, b.species_id,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id = 4), 0) AS ready_quantity,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id = 2), 0) AS active_growth_quantity,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id = 1), 0) AS germinating_quantity,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id = 3), 0) AS hardening_off_quantity,
       COALESCE(SUM(q.quantity) FILTER (WHERE q.stage_id <> 1), 0) AS total_quantity
FROM nursery.batches b JOIN nursery.batch_stage_details q ON q.batch_id = b.id
GROUP BY b.organization_id, b.species_id
HAVING SUM(q.quantity) > 0;

CREATE OR REPLACE VIEW nursery.species_projects AS
SELECT DISTINCT b.organization_id, b.species_id, b.project_id
FROM nursery.batches b
WHERE b.project_id IS NOT NULL AND EXISTS (
    SELECT 1 FROM nursery.batch_stage_details d WHERE d.batch_id = b.id AND d.quantity > 0
);

ALTER TABLE nursery.batches
    DROP COLUMN germinating_quantity,
    DROP COLUMN active_growth_quantity,
    DROP COLUMN hardening_off_quantity,
    DROP COLUMN ready_quantity,
    DROP COLUMN latest_observed_germinating_quantity,
    DROP COLUMN latest_observed_active_growth_quantity,
    DROP COLUMN latest_observed_hardening_off_quantity,
    DROP COLUMN latest_observed_ready_quantity;
