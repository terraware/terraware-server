ALTER TABLE funder.published_report_project_metrics
    ADD COLUMN progress_notes TEXT;

ALTER TABLE funder.published_report_standard_metrics
    ADD COLUMN progress_notes TEXT;

ALTER TABLE funder.published_report_system_metrics
    ADD COLUMN progress_notes TEXT;
