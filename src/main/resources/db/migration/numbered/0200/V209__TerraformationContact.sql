CREATE UNIQUE INDEX organization_users_contact_uk
ON organization_users (organization_id)
WHERE role_id = 5;
