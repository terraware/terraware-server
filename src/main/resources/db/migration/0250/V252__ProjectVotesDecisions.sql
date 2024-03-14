-- Add scoring records
CREATE TABLE accelerator.project_vote_decisions (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    phase_id INTEGER NOT NULL REFERENCES accelerator.cohort_phases,
    vote_option_id INTEGER REFERENCES accelerator.vote_options,
    modified_time TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (project_id, phase_id)
);

-- Speed up query by projects
CREATE INDEX ON accelerator.project_vote_decisions(project_id);

