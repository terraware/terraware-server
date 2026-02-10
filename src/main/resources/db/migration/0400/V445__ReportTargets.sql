CREATE TABLE accelerator.project_project_metric_targets (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    project_metric_id BIGINT NOT NULL REFERENCES accelerator.project_metrics ON DELETE CASCADE,
    year INTEGER NOT NULL,
    target INTEGER,

    PRIMARY KEY (project_id, project_metric_id, year)
);

CREATE INDEX ON accelerator.project_project_metric_targets(project_id, year);

CREATE TABLE accelerator.project_standard_metric_targets (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    standard_metric_id BIGINT NOT NULL REFERENCES accelerator.standard_metrics ON DELETE CASCADE,
    year INTEGER NOT NULL,
    target INTEGER,

    PRIMARY KEY (project_id, standard_metric_id, year)
);

CREATE INDEX ON accelerator.project_standard_metric_targets(project_id, year);

CREATE TABLE accelerator.project_system_metric_targets (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    system_metric_id INTEGER NOT NULL REFERENCES accelerator.system_metrics ON DELETE CASCADE,
    year INTEGER NOT NULL,
    target INTEGER,

    PRIMARY KEY (project_id, system_metric_id, year)
);

CREATE INDEX ON accelerator.project_system_metric_targets(project_id, year);

INSERT INTO accelerator.project_project_metric_targets (project_id, project_metric_id, year, target)
    SELECT r.project_id, m.project_metric_id, extract(year from r.end_date), m.target
    FROM accelerator.report_project_metrics as m
    JOIN accelerator.reports as r
    ON r.id = m.report_id
    WHERE r.report_frequency_id = 2
    ON CONFLICT DO NOTHING;

INSERT INTO accelerator.project_standard_metric_targets (project_id, standard_metric_id, year, target)
    SELECT r.project_id, m.standard_metric_id, extract(year from r.end_date), m.target
    FROM accelerator.report_standard_metrics as m
        JOIN accelerator.reports as r
    ON r.id = m.report_id
    WHERE r.report_frequency_id = 2
    ON CONFLICT DO NOTHING;

INSERT INTO accelerator.project_system_metric_targets (project_id, system_metric_id, year, target)
    SELECT r.project_id, m.system_metric_id, extract(year from r.end_date), m.target
    FROM accelerator.report_system_metrics as m
        JOIN accelerator.reports as r
    ON r.id = m.report_id
    WHERE r.report_frequency_id = 2
    ON CONFLICT DO NOTHING;

CREATE TABLE funder.published_project_metric_targets (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    project_metric_id BIGINT NOT NULL REFERENCES accelerator.project_metrics ON DELETE CASCADE,
    year INTEGER NOT NULL,
    target INTEGER,

    PRIMARY KEY (project_id, project_metric_id, year)
);

CREATE INDEX ON funder.published_project_metric_targets(project_id, year);

CREATE TABLE funder.published_standard_metric_targets (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    standard_metric_id BIGINT NOT NULL REFERENCES accelerator.standard_metrics ON DELETE CASCADE,
    year INTEGER NOT NULL,
    target INTEGER,

    PRIMARY KEY (project_id, standard_metric_id, year)
);

CREATE INDEX ON funder.published_standard_metric_targets(project_id, year);

CREATE TABLE funder.published_system_metric_targets (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    system_metric_id INTEGER NOT NULL REFERENCES accelerator.system_metrics ON DELETE CASCADE,
    year INTEGER NOT NULL,
    target INTEGER,

    PRIMARY KEY (project_id, system_metric_id, year)
);

CREATE INDEX ON funder.published_system_metric_targets(project_id, year);

INSERT INTO funder.published_project_metric_targets (project_id, project_metric_id, year, target)
    SELECT pr.project_id, m.project_metric_id, extract(year from pr.end_date), m.target
    FROM funder.published_report_project_metrics as m
        JOIN funder.published_reports as pr
    ON pr.report_id = m.report_id
    WHERE pr.report_frequency_id = 2
    ON CONFLICT DO NOTHING;

INSERT INTO funder.published_standard_metric_targets (project_id, standard_metric_id, year, target)
    SELECT pr.project_id, m.standard_metric_id, extract(year from pr.end_date), m.target
    FROM funder.published_report_standard_metrics as m
        JOIN funder.published_reports as pr
    ON pr.report_id = m.report_id
    WHERE pr.report_frequency_id = 2
    ON CONFLICT DO NOTHING;

INSERT INTO funder.published_system_metric_targets (project_id, system_metric_id, year, target)
    SELECT pr.project_id, m.system_metric_id, extract(year from pr.end_date), m.target
    FROM funder.published_report_system_metrics as m
        JOIN funder.published_reports as pr
    ON pr.report_id = m.report_id
    WHERE pr.report_frequency_id = 2
    ON CONFLICT DO NOTHING;
