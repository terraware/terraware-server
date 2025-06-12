ALTER TABLE projects DROP CONSTRAINT projects_organization_id_fkey;
ALTER TABLE projects ADD FOREIGN KEY (organization_id) REFERENCES organizations ON DELETE CASCADE;
