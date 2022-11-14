-- Store geometry values in whatever coordinate system was used in the source data (that is, retain
-- the exact original values when importing); we will transform at read time.

-- Need to recreate the view because we're changing the data type of one of its columns.
DROP VIEW tracking.planting_site_summaries;

ALTER TABLE tracking.planting_sites ALTER COLUMN boundary TYPE GEOMETRY(MULTIPOLYGON);
ALTER TABLE tracking.planting_zones ALTER COLUMN boundary TYPE GEOMETRY(MULTIPOLYGON);
ALTER TABLE tracking.plots ALTER COLUMN boundary TYPE GEOMETRY(MULTIPOLYGON);

CREATE OR REPLACE VIEW tracking.planting_site_summaries AS
SELECT id,
       organization_id,
       name,
       description,
       boundary,
       created_by,
       created_time,
       modified_by,
       modified_time,
       (SELECT COUNT(*)
        FROM tracking.planting_zones pz
        WHERE ps.id = pz.planting_site_id) AS num_planting_zones,
       (SELECT COUNT(*)
        FROM tracking.planting_zones pz
                 JOIN tracking.plots p ON pz.id = p.planting_zone_id
        WHERE ps.id = pz.planting_site_id) AS num_plots
FROM tracking.planting_sites ps;
