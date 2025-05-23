CREATE TABLE funder.published_reports (
    report_id BIGINT PRIMARY KEY REFERENCES accelerator.reports ON DELETE CASCADE,
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    report_frequency_id INTEGER NOT NULL REFERENCES accelerator.report_frequencies,
    report_quarter_id INTEGER REFERENCES accelerator.report_quarters,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL CHECK (end_date > start_date),
    highlights TEXT,
    published_by BIGINT NOT NULL REFERENCES users,
    published_time TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ON funder.published_reports(project_id);

CREATE TABLE funder.published_report_achievements(
    report_id BIGINT NOT NULL REFERENCES funder.published_reports ON DELETE CASCADE,
    position INT NOT NULL CHECK (position >= 0),
    achievement TEXT NOT NULL,
    PRIMARY KEY (report_id, position)
);

CREATE TABLE funder.published_report_challenges(
    report_id BIGINT NOT NULL REFERENCES funder.published_reports ON DELETE CASCADE,
    position INT NOT NULL CHECK (position >= 0),
    challenge TEXT NOT NULL,
    mitigation_plan TEXT NOT NULL,
    PRIMARY KEY (report_id, position)
);

CREATE TABLE funder.published_report_project_metrics(
    report_id BIGINT NOT NULL REFERENCES funder.published_reports ON DELETE CASCADE,
    project_metric_id BIGINT NOT NULL REFERENCES accelerator.project_metrics,
    target INTEGER,
    value INTEGER,
    underperformance_justification TEXT,

    PRIMARY KEY (report_id, project_metric_id)
);

CREATE TABLE funder.published_report_standard_metrics(
    report_id BIGINT NOT NULL REFERENCES funder.published_reports ON DELETE CASCADE,
    standard_metric_id BIGINT NOT NULL REFERENCES accelerator.standard_metrics,
    target INTEGER,
    value INTEGER,
    underperformance_justification TEXT,

    PRIMARY KEY (report_id, standard_metric_id)
);

CREATE TABLE funder.published_report_system_metrics(
    report_id BIGINT NOT NULL REFERENCES funder.published_reports ON DELETE CASCADE,
    system_metric_id INTEGER NOT NULL REFERENCES accelerator.system_metrics,
    target INTEGER,
    value INTEGER,
    underperformance_justification TEXT,

    PRIMARY KEY (report_id, system_metric_id)
);
