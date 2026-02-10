ALTER TABLE splats
    ADD COLUMN origin_position_x NUMERIC,
    ADD COLUMN origin_position_y NUMERIC,
    ADD COLUMN origin_position_z NUMERIC,
    ADD CONSTRAINT origin_position_all_or_none CHECK (
        (origin_position_x IS NULL AND origin_position_y IS NULL AND origin_position_z IS NULL) OR
        (origin_position_x IS NOT NULL AND origin_position_y IS NOT NULL AND
         origin_position_z IS NOT NULL)
        )
;
