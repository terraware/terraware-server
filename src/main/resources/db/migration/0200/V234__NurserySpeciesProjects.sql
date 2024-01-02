CREATE VIEW nursery.species_projects AS
    SELECT DISTINCT
        organization_id,
        species_id,
        project_id
    FROM nursery.batches
    WHERE project_id IS NOT NULL
    AND (
        germinating_quantity > 0
        OR not_ready_quantity > 0
        OR ready_quantity > 0
    );
