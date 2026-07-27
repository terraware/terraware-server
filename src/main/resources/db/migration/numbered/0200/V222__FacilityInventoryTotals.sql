CREATE VIEW nursery.facility_inventory_totals AS
SELECT batches.organization_id,
       batches.facility_id,
       sum(batches.ready_quantity) AS ready_quantity,
       sum(batches.not_ready_quantity) AS not_ready_quantity,
       sum(batches.germinating_quantity) AS germinating_quantity,
       sum(batches.ready_quantity + batches.not_ready_quantity) AS total_quantity,
       count(distinct batches.species_id) AS total_species
FROM nursery.batches
WHERE batches.ready_quantity > 0
   OR batches.not_ready_quantity > 0
   OR batches.germinating_quantity > 0
GROUP BY batches.organization_id, batches.facility_id;
