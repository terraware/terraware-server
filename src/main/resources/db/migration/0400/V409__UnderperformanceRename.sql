ALTER TABLE accelerator.report_project_metrics
    RENAME COLUMN underperformance_justification TO projects_comments;

ALTER TABLE accelerator.report_standard_metrics
    RENAME COLUMN underperformance_justification TO projects_comments;

ALTER TABLE accelerator.report_system_metrics
    RENAME COLUMN underperformance_justification TO projects_comments;

ALTER TABLE funder.published_report_project_metrics
    RENAME COLUMN underperformance_justification TO projects_comments;

ALTER TABLE funder.published_report_standard_metrics
    RENAME COLUMN underperformance_justification TO projects_comments;

ALTER TABLE funder.published_report_system_metrics
    RENAME COLUMN underperformance_justification TO projects_comments;
