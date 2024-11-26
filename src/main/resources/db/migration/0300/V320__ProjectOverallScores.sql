CREATE TABLE accelerator.project_overall_scores (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    overall_score FLOAT,
    summary TEXT,
    details_url TEXT,
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    modified_by BIGINT NOT NULL REFERENCES users,
    modified_time TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (project_id)
);
