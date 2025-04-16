ALTER TABLE funder.published_report_project_metrics
    ADD COLUMN status_id INTEGER REFERENCES accelerator.report_metric_statuses;

ALTER TABLE funder.published_report_standard_metrics
    ADD COLUMN status_id INTEGER REFERENCES accelerator.report_metric_statuses;

ALTER TABLE funder.published_report_system_metrics
    ADD COLUMN status_id INTEGER REFERENCES accelerator.report_metric_statuses;
