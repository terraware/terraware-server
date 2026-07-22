CREATE TABLE splat_annotation_media (
    file_id BIGINT PRIMARY KEY REFERENCES files,
    splat_annotation_id BIGINT NOT NULL REFERENCES splat_annotations ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 0),
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,

    UNIQUE (splat_annotation_id, position)
);

CREATE INDEX ON splat_annotation_media (splat_annotation_id);
