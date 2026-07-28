UPDATE accelerator.applications a
SET internal_name = (
    SELECT 'XXX_' || o.name || '_' || a.id
    FROM projects p
    JOIN organizations o ON p.organization_id = o.id
    WHERE p.id = a.project_id
)
WHERE internal_name IS NULL;

ALTER TABLE accelerator.applications ALTER COLUMN internal_name SET NOT NULL;
