ALTER TABLE reports DROP CONSTRAINT one_report_per_quarter;

ALTER TABLE reports ADD COLUMN project_id BIGINT REFERENCES projects (id) ON DELETE SET NULL;
ALTER TABLE reports ADD COLUMN project_name TEXT;

CREATE INDEX ON reports (project_id);

ALTER TABLE reports ADD CONSTRAINT one_project_report_per_quarter
    UNIQUE (organization_id, project_id, year, quarter);
ALTER TABLE reports ADD CONSTRAINT one_org_report_per_quarter
    EXCLUDE (organization_id WITH =, year WITH =, quarter WITH =) WHERE (project_id IS NULL);
