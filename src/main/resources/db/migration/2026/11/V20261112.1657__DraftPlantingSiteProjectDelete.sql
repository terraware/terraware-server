ALTER TABLE tracking.draft_planting_sites
    DROP CONSTRAINT draft_planting_sites_organization_id_project_id_fkey;

ALTER TABLE tracking.draft_planting_sites
    ADD CONSTRAINT draft_planting_sites_organization_id_project_id_fkey
        FOREIGN KEY (organization_id, project_id) REFERENCES projects (organization_id, id)
            ON DELETE SET NULL (project_id);
