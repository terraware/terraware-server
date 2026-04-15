CREATE TABLE organization_media_files (
    file_id BIGINT PRIMARY KEY REFERENCES files ON DELETE CASCADE,
    organization_id BIGINT NOT NULL REFERENCES organizations,
    caption TEXT
);

CREATE INDEX ON organization_media_files (organization_id);
