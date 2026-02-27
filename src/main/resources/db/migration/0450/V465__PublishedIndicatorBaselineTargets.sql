CREATE TABLE funder.published_project_indicator_baselines
(
    project_id           BIGINT  NOT NULL REFERENCES projects ON DELETE CASCADE,
    project_indicator_id BIGINT  NOT NULL REFERENCES accelerator.project_indicators ON DELETE CASCADE,
    baseline             NUMERIC,
    end_target           NUMERIC,

    PRIMARY KEY (project_id, project_indicator_id)
);

CREATE INDEX ON funder.published_project_indicator_baselines (project_indicator_id);

CREATE TABLE funder.published_common_indicator_baselines
(
    project_id          BIGINT  NOT NULL REFERENCES projects ON DELETE CASCADE,
    common_indicator_id BIGINT  NOT NULL REFERENCES accelerator.common_indicators ON DELETE CASCADE,
    baseline            NUMERIC,
    end_target          NUMERIC,

    PRIMARY KEY (project_id, common_indicator_id)
);

CREATE INDEX ON funder.published_common_indicator_baselines (common_indicator_id);

CREATE TABLE funder.published_auto_calculated_indicator_baselines
(
    project_id                   BIGINT   NOT NULL REFERENCES projects ON DELETE CASCADE,
    auto_calculated_indicator_id INTEGER  NOT NULL REFERENCES accelerator.auto_calculated_indicators ON DELETE CASCADE,
    baseline                     NUMERIC,
    end_target                   NUMERIC,

    PRIMARY KEY (project_id, auto_calculated_indicator_id)
);

CREATE INDEX ON funder.published_auto_calculated_indicator_baselines (auto_calculated_indicator_id);
