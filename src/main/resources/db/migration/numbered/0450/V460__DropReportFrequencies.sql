ALTER TABLE accelerator.reports
    DROP COLUMN report_frequency_id;

ALTER TABLE funder.published_reports
    DROP COLUMN report_frequency_id;

ALTER TABLE accelerator.project_report_configs
    DROP COLUMN report_frequency_id;

DROP TABLE accelerator.report_frequencies;
