DROP INDEX IF EXISTS organization_users_contact_uk;

CREATE TABLE project_user_roles
(
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE project_internal_users
(
    project_id           BIGINT NOT NULL,
    user_id              BIGINT NOT NULL,
    project_user_role_id INTEGER REFERENCES project_user_roles,
    role_name            TEXT,
    PRIMARY KEY (project_id, user_id, project_user_role_id),
    CONSTRAINT project_internal_users_role_exclusive CHECK ((project_user_role_id IS NULL) != (role_name IS NULL))
);
