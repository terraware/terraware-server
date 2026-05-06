ALTER TABLE splats
    ADD COLUMN origin_position GEOMETRY(PointZ, 0),
    ADD COLUMN camera_position GEOMETRY(PointZ, 0);

UPDATE splats
SET origin_position = CASE
    WHEN origin_position_x IS NOT NULL
        THEN ST_MakePoint(origin_position_x, origin_position_y, origin_position_z)
    END,
    camera_position = CASE
        WHEN camera_position_x IS NOT NULL
            THEN ST_MakePoint(camera_position_x, camera_position_y, camera_position_z)
        END;

ALTER TABLE splats
    DROP CONSTRAINT origin_position_all_or_none,
    DROP CONSTRAINT camera_position_all_or_none,
    DROP COLUMN origin_position_x,
    DROP COLUMN origin_position_y,
    DROP COLUMN origin_position_z,
    DROP COLUMN camera_position_x,
    DROP COLUMN camera_position_y,
    DROP COLUMN camera_position_z;

ALTER TABLE splat_annotations
    ADD COLUMN position        GEOMETRY(PointZ, 0),
    ADD COLUMN camera_position GEOMETRY(PointZ, 0);

UPDATE splat_annotations
SET position        = ST_MakePoint(position_x, position_y, position_z),
    camera_position = CASE
        WHEN camera_position_x IS NOT NULL
            THEN ST_MakePoint(camera_position_x, camera_position_y, camera_position_z)
        END;

ALTER TABLE splat_annotations
    ALTER COLUMN position SET NOT NULL,
    DROP CONSTRAINT camera_position_all_or_none,
    DROP COLUMN position_x,
    DROP COLUMN position_y,
    DROP COLUMN position_z,
    DROP COLUMN camera_position_x,
    DROP COLUMN camera_position_y,
    DROP COLUMN camera_position_z;
