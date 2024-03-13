-- Add vote decisions enum table (Yes, No, Conditional, Tie)
CREATE TABLE accelerator.vote_decisions (
   id INTEGER PRIMARY KEY,
   name TEXT NOT NULL,
   UNIQUE (name)
);

-- Add scoring records
CREATE TABLE accelerator.project_vote_decisions (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    phase_id INTEGER NOT NULL REFERENCES accelerator.cohort_phases,
    vote_decision_id INTEGER REFERENCES accelerator.vote_decisions,
    modified_time TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (project_id, phase_id)
);

-- Speed up query by projects
CREATE INDEX ON accelerator.project_vote_decisions(project_id);

