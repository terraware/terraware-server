CREATE TABLE accelerator.submission_snapshots (
    id BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    file_id BIGINT REFERENCES files ON DELETE CASCADE,
    submission_id BIGINT NOT NULL REFERENCES accelerator.submissions
);

CREATE INDEX ON accelerator.submission_snapshots (file_id);
CREATE INDEX ON accelerator.submission_snapshots (submission_id);
