-- Add vote options enum table (YES, CONDITIONAL, NO)
CREATE TABLE accelerator.vote_options (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    UNIQUE (name)
);

-- Add voting records
CREATE TABLE accelerator.project_votes (
    user_id BIGINT NOT NULL REFERENCES users ON DELETE CASCADE,
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    phase_id INTEGER NOT NULL REFERENCES accelerator.cohort_phases,
    vote_option_id INTEGER REFERENCES accelerator.vote_options,
    conditional_info TEXT,
    PRIMARY KEY (user_id, project_id, phase_id)
);

-- Speed up query by projects
CREATE INDEX ON accelerator.project_votes(project_id);

