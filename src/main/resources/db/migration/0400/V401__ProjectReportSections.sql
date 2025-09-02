ALTER TABLE accelerator.reports
    ADD COLUMN financial_summaries TEXT,
    ADD COLUMN additional_comments TEXT;

ALTER TABLE funder.published_reports
    ADD COLUMN financial_summaries TEXT,
    ADD COLUMN additional_comments TEXT;
