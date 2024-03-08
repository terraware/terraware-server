ALTER TABLE accelerator.submission_documents ADD COLUMN project_id BIGINT REFERENCES projects (id);

UPDATE accelerator.submission_documents sd
SET project_id = (
    SELECT project_id
    FROM accelerator.submissions s
    WHERE sd.submission_id = s.id
);

ALTER TABLE accelerator.submission_documents ALTER COLUMN project_id SET NOT NULL;

ALTER TABLE accelerator.submission_documents
    ADD CONSTRAINT no_duplicate_names_for_project
        UNIQUE (project_id, name);
