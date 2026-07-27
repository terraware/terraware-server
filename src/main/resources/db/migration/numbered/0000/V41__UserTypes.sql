CREATE TABLE user_types (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

INSERT INTO user_types
VALUES (1, 'Individual');
INSERT INTO user_types
VALUES (2, 'Super Admin');
INSERT INTO user_types
VALUES (3, 'API Client');

ALTER TABLE users ADD COLUMN user_type_id INTEGER REFERENCES user_types (id);

UPDATE users
SET user_type_id = CASE WHEN super_admin THEN 2 ELSE 1 END
WHERE users.user_type_id IS NULL;

ALTER TABLE users ALTER COLUMN user_type_id SET NOT NULL;
ALTER TABLE users DROP COLUMN super_admin;
