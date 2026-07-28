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
        WHERE ps.id = pz.planting_site_id) AS num_plots,
       time_zone
FROM tracking.planting_sites ps;
