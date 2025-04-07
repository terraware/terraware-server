CREATE TABLE funder.funder_reports (
    report_id BIGINT PRIMARY KEY REFERENCES accelerator.reports,
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    report_frequency_id INTEGER NOT NULL REFERENCES accelerator.report_frequencies,
    report_quarter_id INTEGER REFERENCES accelerator.report_quarters,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL CHECK (end_date > start_date),
    highlights TEXT,
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    modified_by BIGINT NOT NULL REFERENCES users,
    modified_time TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ON funder.funder_reports(project_id);

CREATE TABLE funder.funder_report_achievements(
    report_id BIGINT NOT NULL REFERENCES funder.funder_reports ON DELETE CASCADE,
    position INT NOT NULL CHECK (position >= 0),
    achievement TEXT NOT NULL,
    PRIMARY KEY (report_id, position)
);

CREATE TABLE funder.funder_report_challenges(
    report_id BIGINT NOT NULL REFERENCES funder.funder_reports ON DELETE CASCADE,
    position INT NOT NULL CHECK (position >= 0),
    challenge TEXT NOT NULL,
    mitigation_plan TEXT NOT NULL,
    PRIMARY KEY (report_id, position)
);

CREATE TABLE funder.funder_report_project_metrics(
    report_id BIGINT NOT NULL REFERENCES funder.funder_reports ON DELETE CASCADE,
    project_metric_id BIGINT NOT NULL REFERENCES accelerator.project_metrics,
    target INTEGER,
    value INTEGER,
    underperformance_justification TEXT,

    PRIMARY KEY (report_id, project_metric_id)
);

CREATE TABLE funder.funder_report_standard_metrics(
    report_id BIGINT NOT NULL REFERENCES funder.funder_reports ON DELETE CASCADE,
    standard_metric_id BIGINT NOT NULL REFERENCES accelerator.standard_metrics,
    target INTEGER,
    value INTEGER,
    underperformance_justification TEXT,

    PRIMARY KEY (report_id, standard_metric_id)
);

CREATE TABLE funder.funder_report_system_metrics(
    report_id BIGINT NOT NULL REFERENCES funder.funder_reports ON DELETE CASCADE,
    system_metric_id INTEGER NOT NULL REFERENCES accelerator.system_metrics,
    target INTEGER,
    value INTEGER,
    underperformance_justification TEXT,

    PRIMARY KEY (report_id, system_metric_id)
);
