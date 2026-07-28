INSERT INTO user_types (id, name)
VALUES (4, 'System');

-- Auth ID of 'DISABLED' has no special meaning but will never match a Keycloak UUID, meaning it is
-- impossible to authenticate as this user.
INSERT INTO users (auth_id, email, first_name, last_name, created_time, modified_time, user_type_id)
VALUES ('DISABLED', 'system', 'Terraware', 'System', NOW(), NOW(), 4);
