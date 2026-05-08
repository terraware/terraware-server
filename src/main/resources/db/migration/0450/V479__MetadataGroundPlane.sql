ALTER TABLE splats
    ALTER COLUMN ground_plane TYPE GEOMETRY(MULTIPOINT, 0),
    ADD COLUMN average_camera_height NUMERIC;
