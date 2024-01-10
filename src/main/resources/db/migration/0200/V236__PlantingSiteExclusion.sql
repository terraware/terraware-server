ALTER TABLE tracking.planting_sites ADD COLUMN exclusion GEOMETRY(MultiPolygon);

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
                 JOIN tracking.planting_subzones sz ON pz.id = sz.planting_zone_id
        WHERE ps.id = pz.planting_site_id) AS num_planting_subzones,
       time_zone,
       project_id,
       exclusion
FROM tracking.planting_sites ps;
