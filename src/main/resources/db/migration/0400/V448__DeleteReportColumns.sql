DELETE FROM accelerator.reports
WHERE report_frequency_id = 2;

DELETE FROM accelerator.project_report_configs
WHERE report_frequency_id = 2;

DELETE FROM accelerator.report_frequencies
WHERE id = 2;

ALTER TABLE accelerator.report_project_metrics
DROP COLUMN target;

ALTER TABLE accelerator.report_standard_metrics
DROP COLUMN target;

ALTER TABLE accelerator.report_system_metrics
DROP COLUMN target;

ALTER TABLE funder.published_report_project_metrics
DROP COLUMN target;

ALTER TABLE funder.published_report_standard_metrics
DROP COLUMN target;

ALTER TABLE funder.published_report_system_metrics
DROP COLUMN target;
