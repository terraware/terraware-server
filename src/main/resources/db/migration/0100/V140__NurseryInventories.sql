CREATE OR REPLACE VIEW nursery.inventories AS
    SELECT organization_id,
           species_id,
           SUM(ready_quantity)                      AS ready_quantity,
           SUM(not_ready_quantity)                  AS not_ready_quantity,
           SUM(germinating_quantity)                AS germinating_quantity,
           SUM(ready_quantity + not_ready_quantity) AS total_quantity
    FROM nursery.batches
    GROUP BY organization_id, species_id
    HAVING SUM(ready_quantity + not_ready_quantity + germinating_quantity) > 0;

CREATE OR REPLACE VIEW nursery.facility_inventories AS
    SELECT organization_id,
           species_id,
           facility_id,
           SUM(ready_quantity)                      AS ready_quantity,
           SUM(not_ready_quantity)                  AS not_ready_quantity,
           SUM(germinating_quantity)                AS germinating_quantity,
           SUM(ready_quantity + not_ready_quantity) AS total_quantity
    FROM nursery.batches
    GROUP BY organization_id, species_id, facility_id
    HAVING SUM(ready_quantity + not_ready_quantity + germinating_quantity) > 0;
