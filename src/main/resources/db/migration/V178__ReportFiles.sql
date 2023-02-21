CREATE TABLE report_files (
    file_id BIGINT PRIMARY KEY REFERENCES files ON DELETE CASCADE,
    report_id BIGINT NOT NULL REFERENCES reports
);

CREATE INDEX ON report_files (report_id);
