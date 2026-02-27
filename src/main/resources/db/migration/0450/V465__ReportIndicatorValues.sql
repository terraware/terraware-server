-- Report indicators
ALTER TABLE accelerator.report_auto_calculated_indicators ALTER COLUMN system_value TYPE NUMERIC;
ALTER TABLE accelerator.report_auto_calculated_indicators ALTER COLUMN value TYPE NUMERIC;

ALTER TABLE accelerator.report_common_indicators ALTER COLUMN value TYPE NUMERIC;

ALTER TABLE accelerator.report_project_indicators ALTER COLUMN value TYPE NUMERIC;

-- Published report indicators
ALTER TABLE funders.published_report_auto_calculated_indicators ALTER COLUMN value TYPE NUMERIC;

ALTER TABLE funders.published_report_common_indicators ALTER COLUMN value TYPE NUMERIC;

ALTER TABLE funders.published_report_project_indicators ALTER COLUMN value TYPE NUMERIC;

-- Report targets
ALTER TABLE accelerator.report_auto_calculated_indicator_targets ALTER COLUMN target TYPE NUMERIC;

ALTER TABLE accelerator.report_common_indicator_targets ALTER COLUMN target TYPE NUMERIC;

ALTER TABLE accelerator.report_project_indicator_targets ALTER COLUMN target TYPE NUMERIC;

-- Published report targets
ALTER TABLE funders.published_auto_calculated_indicator_targets ALTER COLUMN target TYPE NUMERIC;

ALTER TABLE funders.published_common_indicator_targets ALTER COLUMN target TYPE NUMERIC;

ALTER TABLE funders.published_project_indicator_targets ALTER COLUMN target TYPE NUMERIC;

