CREATE TABLE splat_information (
    file_id BIGINT PRIMARY KEY REFERENCES files,
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    modified_by BIGINT NOT NULL REFERENCES users,
    modified_time TIMESTAMP WITH TIME ZONE NOT NULL,
    origin_position_x NUMERIC,
    origin_position_y NUMERIC,
    origin_position_z NUMERIC,
    CONSTRAINT origin_position_all_or_none CHECK (
        (origin_position_x IS NULL AND origin_position_y IS NULL AND origin_position_z IS NULL) OR
        (origin_position_x IS NOT NULL AND origin_position_y IS NOT NULL AND
         origin_position_z IS NOT NULL)
        )
);