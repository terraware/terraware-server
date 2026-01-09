ALTER TABLE projects ADD COLUMN cohort_id BIGINT REFERENCES accelerator.cohorts (id);
ALTER TABLE projects ADD COLUMN phase_id INTEGER REFERENCES accelerator.cohort_phases (id);

UPDATE projects
SET (cohort_id, phase_id) = (
    SELECT ap.cohort_id, c.phase_id
    FROM accelerator.participants ap
    LEFT JOIN accelerator.cohorts c on ap.cohort_id = c.id
    WHERE ap.id = projects.participant_id
)
WHERE participant_id IS NOT NULL;
