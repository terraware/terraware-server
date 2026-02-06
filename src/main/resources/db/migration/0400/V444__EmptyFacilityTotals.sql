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
GROUP BY batches.organization_id, batches.facility_id
;
