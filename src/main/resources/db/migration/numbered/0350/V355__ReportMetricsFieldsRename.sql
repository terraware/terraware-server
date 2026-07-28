ALTER TABLE accelerator.report_project_metrics
    RENAME COLUMN notes TO underperformance_justification;

ALTER TABLE accelerator.report_project_metrics
    RENAME COLUMN internal_comment TO progress_notes;

ALTER TABLE accelerator.report_standard_metrics
    RENAME COLUMN notes TO underperformance_justification;

ALTER TABLE accelerator.report_standard_metrics
    RENAME COLUMN internal_comment TO progress_notes;

ALTER TABLE accelerator.report_system_metrics
    RENAME COLUMN notes TO underperformance_justification;

ALTER TABLE accelerator.report_system_metrics
    RENAME COLUMN internal_comment TO progress_notes;
