ALTER TABLE tracking.planting_sites ADD COLUMN grid_origin GEOMETRY(Point);

UPDATE tracking.planting_sites ps
SET grid_origin = ST_SetSRID(ST_MakePoint(ST_XMin(boundary), ST_YMin(boundary)), ST_SRID(boundary))
WHERE boundary IS NOT NULL
AND EXISTS (
    SELECT 1
    FROM tracking.planting_zones pz
    WHERE pz.planting_site_id = ps.id
);
