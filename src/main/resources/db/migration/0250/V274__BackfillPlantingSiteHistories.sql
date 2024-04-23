-- Insert initial history records for sites created before we started populating history at site
-- creation time.

INSERT INTO tracking.planting_site_histories
    (planting_site_id, created_by, created_time, boundary, grid_origin, exclusion)
SELECT id, created_by, created_time, boundary, grid_origin, exclusion
FROM tracking.planting_sites ps
WHERE boundary IS NOT NULL
AND NOT EXISTS (
    SELECT 1
    FROM tracking.planting_site_histories psh
    WHERE ps.id = psh.planting_site_id
);

INSERT INTO tracking.planting_zone_histories
    (planting_site_history_id, planting_zone_id, name, boundary)
SELECT psh.id, pz.id, pz.name, pz.boundary
FROM tracking.planting_site_histories psh
JOIN tracking.planting_zones pz USING (planting_site_id)
WHERE NOT EXISTS (
    SELECT 1
    FROM tracking.planting_zone_histories pzh
    WHERE psh.id = pzh.planting_site_history_id
    AND pz.id = pzh.planting_zone_id
);

INSERT INTO tracking.planting_subzone_histories
    (planting_zone_history_id, planting_subzone_id, name, full_name, boundary)
SELECT pzh.id, psz.id, psz.name, psz.full_name, psz.boundary
FROM tracking.planting_zone_histories pzh
JOIN tracking.planting_subzones psz USING (planting_zone_id)
WHERE NOT EXISTS (
    SELECT 1
    FROM tracking.planting_subzone_histories pszh
    WHERE pzh.id = pszh.planting_zone_history_id
    AND psz.id = pszh.planting_subzone_id
);
