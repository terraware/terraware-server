CREATE TABLE organization_report_settings (
    organization_id BIGINT PRIMARY KEY REFERENCES organizations ON DELETE CASCADE,
    is_enabled BOOLEAN NOT NULL
);

CREATE TABLE project_report_settings (
    project_id BIGINT PRIMARY KEY REFERENCES projects ON DELETE CASCADE,
    is_enabled BOOLEAN NOT NULL
);
