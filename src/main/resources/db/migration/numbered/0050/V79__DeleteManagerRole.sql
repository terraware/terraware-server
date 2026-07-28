-- Upgrade all existing managers to admins.
UPDATE organization_users SET role_id = 3 WHERE role_id = 2;
DELETE FROM roles WHERE id = 2;
