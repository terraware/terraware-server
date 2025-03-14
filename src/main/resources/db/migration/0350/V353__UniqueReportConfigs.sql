-- Keep only one row per (project_id, frequency)
DELETE FROM accelerator.project_report_configs
WHERE id NOT IN (
    SELECT DISTINCT ON (project_id, report_frequency_id) id
    FROM accelerator.project_report_configs
    ORDER BY project_id, report_frequency_id, id
);

ALTER TABLE accelerator.project_report_configs
    ADD CONSTRAINT unique_project_frequency UNIQUE (project_id, report_frequency_id);
