-- Use the generic "files" table for image information, but caption can change over time so
-- needs to be pulled from the variable value.
ALTER TABLE variable_image_values ADD COLUMN file_id BIGINT NOT NULL REFERENCES files;
ALTER TABLE variable_image_values DROP COLUMN storage_url;
ALTER TABLE variable_image_values DROP COLUMN description;

ALTER TABLE files DROP COLUMN caption;
