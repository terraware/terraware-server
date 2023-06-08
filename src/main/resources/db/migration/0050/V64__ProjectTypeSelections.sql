-- We want to support multiple types per project. Our naming convention would be to create a
-- project_types table to hold (project ID, type ID) pairs, but the list of available types is
-- also in a table called project_types.
CREATE TABLE project_type_selections (
    project_id BIGINT NOT NULL REFERENCES projects,
    project_type_id INTEGER NOT NULL REFERENCES project_types,
    PRIMARY KEY (project_id, project_type_id)
);

INSERT INTO project_type_selections (project_id, project_type_id)
SELECT id, type_id
FROM projects
WHERE type_id IS NOT NULL;

ALTER TABLE projects DROP COLUMN type_id;
