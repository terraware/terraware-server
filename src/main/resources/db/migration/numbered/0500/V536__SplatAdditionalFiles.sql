CREATE TABLE splat_additional_file_types
(
  id   INTEGER PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE splat_additional_files
(
  file_id       BIGINT  PRIMARY KEY REFERENCES files ON DELETE CASCADE,
  splat_file_id BIGINT  NOT NULL REFERENCES splats ON DELETE CASCADE,
  type_id       INTEGER NOT NULL REFERENCES splat_additional_file_types
);

CREATE INDEX ON splat_additional_files (splat_file_id);

ALTER TABLE splats DROP COLUMN data_file_id;
