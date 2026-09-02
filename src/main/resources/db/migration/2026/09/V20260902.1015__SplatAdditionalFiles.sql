CREATE TABLE splat_additional_files
(
    file_id       BIGINT PRIMARY KEY REFERENCES files ON DELETE CASCADE,
    splat_file_id BIGINT NOT NULL REFERENCES splats,
    type          TEXT   NOT NULL,

    UNIQUE (splat_file_id, type)
);

ALTER TABLE splats
    DROP COLUMN data_file_id;
