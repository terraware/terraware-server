CREATE TABLE accelerator.report_metric_statuses (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

ALTER TABLE accelerator.report_standard_metrics
    ADD COLUMN status_id INTEGER REFERENCES accelerator.report_metric_statuses;

ALTER TABLE accelerator.report_system_metrics
    ADD COLUMN status_id INTEGER REFERENCES accelerator.report_metric_statuses;

ALTER TABLE accelerator.report_project_metrics
    ADD COLUMN status_id INTEGER REFERENCES accelerator.report_metric_statuses;
