ALTER TABLE accelerator.report_auto_calculated_indicators
    ADD COLUMN supporting_document_url TEXT;

ALTER TABLE accelerator.report_common_indicators
    ADD COLUMN supporting_document_url TEXT;

ALTER TABLE accelerator.report_project_indicators
    ADD COLUMN supporting_document_url TEXT;

ALTER TABLE funder.published_report_auto_calculated_indicators
    ADD COLUMN supporting_document_url TEXT;

ALTER TABLE funder.published_report_common_indicators
    ADD COLUMN supporting_document_url TEXT;

ALTER TABLE funder.published_report_project_indicators
    ADD COLUMN supporting_document_url TEXT;
