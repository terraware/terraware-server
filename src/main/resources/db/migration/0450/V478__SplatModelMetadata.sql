ALTER TABLE splats
    ADD COLUMN sky_color TEXT,
    ADD COLUMN ground_color TEXT,
    ADD COLUMN ground_plane GEOMETRY(POLYGON, 0),
    ADD COLUMN scene_bounds GEOMETRY(POINTZM, 0),
    ADD COLUMN sky_radius NUMERIC
;
