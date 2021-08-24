-- Make sure all the coordinates in the features table are 3D points in spherical Mercator space.
-- Transform the coordinates of any existing features, including copying the altitude from the
-- separate column (in the previous data model) to the Z coordinates of the geometries.
UPDATE features
SET geom = st_transform(
        st_setsrid(
                st_force3d(geom, COALESCE(altitude, 0)),
                CASE
                    WHEN st_srid(geom) = 0
                        THEN 3857
                    ELSE st_srid(geom)
                    END),
        3857);

-- Constrain new geometry values: they must be 3D points in the correct coordinate system.
ALTER TABLE features ALTER COLUMN geom TYPE geometry(GeometryZ, 3857);

-- Altitude is now the Z axis in the coordinate system.
ALTER TABLE features DROP COLUMN altitude;
