CREATE TABLE accelerator.report_photos (
    file_id BIGINT PRIMARY KEY REFERENCES files ON DELETE CASCADE,
    report_id BIGINT NOT NULL REFERENCES accelerator.reports ON DELETE CASCADE ,
    deleted BOOLEAN NOT NULL,
    caption TEXT,

    UNIQUE(report_id, file_id)
);

CREATE INDEX ON accelerator.report_photos(report_id);

CREATE TABLE funder.published_report_photos (
    file_id BIGINT PRIMARY KEY REFERENCES files ON DELETE CASCADE,
    report_id BIGINT NOT NULL REFERENCES funder.published_reports ON DELETE CASCADE,
    caption TEXT,

    FOREIGN KEY (report_id, file_id) REFERENCES accelerator.report_photos(report_id, file_id)
);

CREATE INDEX ON funder.published_report_photos(report_id);
