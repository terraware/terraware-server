-- All offenders come from the plot photos upload endpoint. First find the affect rows and then
-- decide whether to flip the coordinates by first checking their validity, then checking proximity
-- to their planting site
WITH candidates AS (
    SELECT
        f.id,
        f.geolocation AS original_geom,
        ST_FlipCoordinates(f.geolocation) AS flipped_geom,
        ST_Centroid(ps.boundary) AS reference_geom
    FROM files f
    JOIN tracking.observation_media_files omf
        ON omf.file_id = f.id
    JOIN tracking.observations obs
        ON obs.id = omf.observation_id
    JOIN tracking.planting_sites ps
        ON ps.id = obs.planting_site_id
    WHERE f.geolocation IS NOT NULL
),
corrected AS (
    SELECT
        id,
        CASE
            -- 1. Original coordinate is invalid, so flip immediately
            WHEN ST_X(original_geom) NOT BETWEEN -180 AND 180
              OR ST_Y(original_geom) NOT BETWEEN -90 AND 90
            THEN flipped_geom

            -- 2. Original is valid, but flipped version is invalid, so leave alone
            WHEN ST_X(flipped_geom) NOT BETWEEN -180 AND 180
              OR ST_Y(flipped_geom) NOT BETWEEN -90 AND 90
            THEN original_geom

            -- No reference point available, so leave alone
            WHEN reference_geom IS NULL
            THEN original_geom

            -- 3. Both are valid, use whichever is closer to reference centroid
            WHEN ST_Distance(flipped_geom::geography, reference_geom::geography)
               < ST_Distance(original_geom::geography, reference_geom::geography)
            THEN flipped_geom

            ELSE original_geom
        END AS corrected_geom
    FROM candidates
)

UPDATE files f
SET geolocation = c.corrected_geom
FROM corrected c
WHERE f.id = c.id
AND f.geolocation IS DISTINCT FROM c.corrected_geom;

