CREATE TABLE funder.published_activities (
    activity_id      BIGINT PRIMARY KEY REFERENCES accelerator.activities,
    project_id       BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    activity_type_id INTEGER NOT NULL REFERENCES accelerator.activity_types,
    activity_date    DATE NOT NULL,
    description      TEXT NOT NULL,
    is_highlight     BOOLEAN NOT NULL,
    published_by     BIGINT NOT NULL REFERENCES users,
    published_time   TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ON funder.published_activities (project_id);

CREATE TABLE funder.published_activity_media_files (
    file_id                BIGINT PRIMARY KEY REFERENCES files,
    activity_id            BIGINT NOT NULL REFERENCES funder.published_activities,
    activity_media_type_id INTEGER NOT NULL REFERENCES accelerator.activity_media_types,
    is_cover_photo         BOOLEAN NOT NULL,
    is_hidden_on_map       BOOLEAN NOT NULL,
    list_position          INTEGER NOT NULL,
    captured_date          DATE NOT NULL,
    caption                TEXT,
    geolocation            GEOMETRY(POINT),

    UNIQUE (activity_id, list_position) DEFERRABLE INITIALLY DEFERRED
);
