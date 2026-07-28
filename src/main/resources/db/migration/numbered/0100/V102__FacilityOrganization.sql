ALTER TABLE facilities ADD COLUMN organization_id BIGINT REFERENCES organizations;
UPDATE facilities
SET organization_id = (
    SELECT organization_id
    FROM sites
    JOIN projects ON sites.project_id = projects.id
    WHERE facilities.site_id = sites.id
)
WHERE organization_id IS NULL;

ALTER TABLE facilities ALTER COLUMN organization_id SET NOT NULL;

CREATE INDEX ON facilities (organization_id);
