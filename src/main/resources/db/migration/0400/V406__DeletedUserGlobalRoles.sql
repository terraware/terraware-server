DELETE FROM user_global_roles
USING users
WHERE users.id = user_global_roles.user_id
AND users.deleted_time IS NOT NULL;
