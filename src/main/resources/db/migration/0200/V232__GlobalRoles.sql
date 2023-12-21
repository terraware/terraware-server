CREATE TABLE global_roles (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE user_global_roles (
    user_id BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
    global_role_id INTEGER NOT NULL REFERENCES global_roles ON DELETE CASCADE,

    PRIMARY KEY (user_id, global_role_id)
);

CREATE INDEX ON user_global_roles (user_id);
