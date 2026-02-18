CREATE TABLE accelerator.project_modules (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    module_id BIGINT NOT NULL REFERENCES accelerator.modules ON DELETE CASCADE,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    title TEXT NOT NULL,

    PRIMARY KEY (project_id, module_id),
    CONSTRAINT dates_start_before_end CHECK (start_date <= end_date)
);

CREATE INDEX ON accelerator.project_modules (module_id);

INSERT INTO accelerator.project_modules (project_id, module_id, start_date, end_date, title)
SELECT p.id, cm.module_id, cm.start_date, cm.end_date, cm.title
FROM accelerator.cohort_modules cm
JOIN projects p ON cm.cohort_id = p.cohort_id;
