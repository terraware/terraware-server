INSERT INTO global_roles (id, name)
VALUES (1, 'Super-Admin')
ON CONFLICT DO NOTHING;

INSERT INTO user_global_roles (user_id, global_role_id)
SELECT id, 1
FROM users
WHERE user_type_id = 2
ON CONFLICT DO NOTHING;

UPDATE users
SET user_type_id = 1
WHERE user_type_id = 2;

DELETE FROM user_types WHERE id = 2;
