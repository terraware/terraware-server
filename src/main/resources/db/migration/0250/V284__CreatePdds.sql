-- Create an initial empty variable manifest (which requires a methodology) so that we can create
-- PDDs in dev environments before we implement the code to manage manifests.
INSERT INTO methodologies (name)
VALUES ('Afforestation, Reforestation and Revegetation');

WITH system_user AS (SELECT id FROM users WHERE user_type_id = 4)
INSERT INTO variable_manifests (methodology_id, created_by, created_time)
VALUES(1, (SELECT id FROM system_user), NOW());
