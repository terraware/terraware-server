CREATE TABLE accelerator.report_achievements(
    report_id BIGINT NOT NULL REFERENCES accelerator.reports ON DELETE CASCADE,
    position INT NOT NULL CHECK (position >= 0),
    achievement TEXT NOT NULL,
    PRIMARY KEY (report_id, position)
);

CREATE TABLE accelerator.report_challenges(
    report_id BIGINT NOT NULL REFERENCES accelerator.reports ON DELETE CASCADE,
    position INT NOT NULL CHECK (position >= 0),
    challenge TEXT NOT NULL,
    mitigation_plan TEXT NOT NULL,
    PRIMARY KEY (report_id, position)
);
