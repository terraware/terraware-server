CREATE TABLE file_access_tokens (
    token TEXT PRIMARY KEY,
    file_id BIGINT NOT NULL REFERENCES files ON DELETE CASCADE,
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_time TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ON file_access_tokens (expires_time);
CREATE INDEX ON file_access_tokens (file_id);
