-- Add score category enum table (Carbon, Finance, Forestry... )
CREATE TABLE accelerator.score_categories (
   id INTEGER PRIMARY KEY,
   name TEXT NOT NULL,
   UNIQUE (name)
);

-- Add scoring records
CREATE TABLE accelerator.project_scores (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    phase_id INTEGER NOT NULL REFERENCES accelerator.cohort_phases,
    score_category_id INTEGER NOT NULL REFERENCES accelerator.score_categories,
    score INTEGER CHECK (score >= -2 AND score <= 2),
    qualitative TEXT,
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    modified_by BIGINT NOT NULL REFERENCES users,
    modified_time TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (project_id, phase_id, score_category_id)
);

-- Speed up query by projects
CREATE INDEX ON accelerator.project_scores(project_id);

