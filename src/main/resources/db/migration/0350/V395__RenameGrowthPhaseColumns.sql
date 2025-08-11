ALTER TABLE nursery.batches
    RENAME COLUMN not_ready_quantity TO active_growth_quantity;
ALTER TABLE nursery.batches
    RENAME COLUMN latest_observed_not_ready_quantity TO latest_observed_active_growth_quantity;


ALTER TABLE nursery.batch_quantity_history
    RENAME COLUMN not_ready_quantity TO active_growth_quantity;
;

ALTER TABLE nursery.batch_withdrawals
    RENAME COLUMN not_ready_quantity_withdrawn TO active_growth_quantity_withdrawn;
;

-- rename not_ready to active_growth, previous version was 393
    ALTER VIEW nursery.facility_inventories RENAME COLUMN not_ready_quantity TO active_growth_quantity;

CREATE OR REPLACE VIEW nursery.facility_inventories AS
SELECT organization_id,
       species_id,
       facility_id,
       SUM(ready_quantity)                                                   AS ready_quantity,
       SUM(active_growth_quantity)                                           AS active_growth_quantity,
       SUM(germinating_quantity)                                             AS germinating_quantity,
       SUM(hardening_off_quantity)                                           AS hardening_off_quantity,
       SUM(ready_quantity + active_growth_quantity + hardening_off_quantity) AS total_quantity
FROM nursery.batches
GROUP BY organization_id, species_id, facility_id
HAVING
    SUM(ready_quantity + active_growth_quantity + germinating_quantity + hardening_off_quantity) >
    0;

-- rename not_ready to active_growth, previous version was 393
    ALTER VIEW nursery.facility_inventory_totals RENAME COLUMN not_ready_quantity TO active_growth_quantity;

CREATE OR REPLACE VIEW nursery.facility_inventory_totals AS
SELECT batches.organization_id,
       batches.facility_id,
       sum(batches.ready_quantity)         AS ready_quantity,
       sum(batches.active_growth_quantity) AS active_growth_quantity,
       sum(batches.germinating_quantity)   AS germinating_quantity,
       sum(batches.hardening_off_quantity) AS hardening_off_quantity,
       sum(batches.ready_quantity + batches.active_growth_quantity +
           batches.hardening_off_quantity) AS total_quantity,
       count(distinct batches.species_id)  AS total_species
FROM nursery.batches
WHERE batches.ready_quantity > 0
   OR batches.active_growth_quantity > 0
   OR batches.germinating_quantity > 0
   OR batches.hardening_off_quantity > 0
GROUP BY batches.organization_id, batches.facility_id
;

-- rename not_ready to active_growth, previous version was 393
    ALTER VIEW nursery.inventories RENAME COLUMN not_ready_quantity TO active_growth_quantity;

CREATE OR REPLACE VIEW nursery.inventories AS
SELECT organization_id,
       species_id,
       SUM(ready_quantity)                                                   AS ready_quantity,
       SUM(active_growth_quantity)                                           AS active_growth_quantity,
       SUM(germinating_quantity)                                             AS germinating_quantity,
       SUM(hardening_off_quantity)                                           AS hardening_off_quantity,
       SUM(ready_quantity + active_growth_quantity + hardening_off_quantity) AS total_quantity
FROM nursery.batches
GROUP BY organization_id, species_id
HAVING
    SUM(ready_quantity + active_growth_quantity + germinating_quantity + hardening_off_quantity) >
    0;

-- rename not_ready to active_growth, previous version was 393
CREATE OR REPLACE VIEW nursery.species_projects AS
SELECT DISTINCT organization_id,
                species_id,
                project_id
FROM nursery.batches
WHERE project_id IS NOT NULL
  AND (
    germinating_quantity > 0
        OR active_growth_quantity > 0
        OR ready_quantity > 0
        OR hardening_off_quantity > 0
    );

-- rename not_ready to active_growth, previous version was 393
CREATE OR REPLACE VIEW nursery.withdrawal_summaries AS
SELECT withdrawals.id,
       withdrawals.facility_id,
       withdrawals.purpose_id,
       withdrawals.withdrawn_date,
       withdrawals.created_by,
       withdrawals.created_time,
       withdrawals.modified_by,
       withdrawals.modified_time,
       withdrawals.destination_facility_id,
       withdrawals.notes,
       withdrawals.undoes_withdrawal_id,
       undoes_withdrawals.withdrawn_date              as undoes_withdrawal_date,
       undone_by_withdrawals.id                       AS undone_by_withdrawal_id,
       undone_by_withdrawals.withdrawn_date           AS undone_by_withdrawal_date,
       facilities.organization_id,
       deliveries.id                                  AS delivery_id,
       totals.total_withdrawn,
       COALESCE(dest_nurseries.name, dest_sites.name) AS destination_name,
       COALESCE(reassignment_subzones.plot_names,
                delivery_subzones.plot_names)         AS planting_subzone_names,
       (EXISTS (SELECT 1
                FROM tracking.plantings p
                WHERE p.delivery_id = deliveries.id
                  AND p.planting_type_id = 2))        AS has_reassignments
FROM nursery.withdrawals withdrawals
         JOIN facilities ON withdrawals.facility_id = facilities.id
         LEFT JOIN tracking.deliveries deliveries
                   ON withdrawals.id = deliveries.withdrawal_id AND withdrawals.purpose_id = 3
         LEFT JOIN nursery.withdrawals AS undone_by_withdrawals
                   ON withdrawals.id = undone_by_withdrawals.undoes_withdrawal_id
         LEFT JOIN nursery.withdrawals AS undoes_withdrawals
                   ON withdrawals.undoes_withdrawal_id = undoes_withdrawals.id
         LEFT JOIN LATERAL ( SELECT COALESCE(sum(bw.germinating_quantity_withdrawn +
                                                 bw.hardening_off_quantity_withdrawn +
                                                 bw.active_growth_quantity_withdrawn +
                                                 bw.ready_quantity_withdrawn),
                                             0::bigint) AS total_withdrawn
                             FROM nursery.batch_withdrawals bw
                             WHERE withdrawals.id = bw.withdrawal_id) totals ON true
         LEFT JOIN LATERAL ( SELECT string_agg(DISTINCT p.full_name, ', '::text
                                               ORDER BY p.full_name) AS plot_names
                             FROM tracking.planting_subzones p
                                      JOIN tracking.plantings pl ON p.id = pl.planting_subzone_id
                             WHERE pl.planting_type_id = 1
                               AND pl.delivery_id = deliveries.id) delivery_subzones ON true
         LEFT JOIN LATERAL ( SELECT ((delivery_subzones.plot_names || ' ('::text) ||
                                     string_agg(p.full_name, ', '::text ORDER BY p.full_name)) ||
                                    ')'::text AS plot_names
                             FROM tracking.planting_subzones p
                                      JOIN tracking.plantings pl ON p.id = pl.planting_subzone_id
                             WHERE pl.planting_type_id = 3
                               AND pl.delivery_id = deliveries.id) reassignment_subzones ON true
         LEFT JOIN LATERAL ( SELECT f.name
                             FROM facilities f
                             WHERE f.id = withdrawals.destination_facility_id
                               AND withdrawals.purpose_id = 1) dest_nurseries ON true
         LEFT JOIN LATERAL ( SELECT ps.name
                             FROM tracking.planting_sites ps
                             WHERE ps.id = deliveries.planting_site_id
                               AND withdrawals.purpose_id = 3) dest_sites ON true;
