CREATE TABLE accelerator.project_indicator_targets (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    project_indicator_id BIGINT NOT NULL REFERENCES accelerator.project_indicators ON DELETE CASCADE,
    baseline NUMERIC,
    end_target NUMERIC,

    PRIMARY KEY (project_id, project_indicator_id)
);

CREATE INDEX ON accelerator.project_indicator_targets (project_indicator_id);

CREATE TABLE accelerator.common_indicator_targets (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    common_indicator_id BIGINT NOT NULL REFERENCES accelerator.common_indicators ON DELETE CASCADE,
    baseline NUMERIC,
    end_target NUMERIC,

    PRIMARY KEY (project_id, common_indicator_id)
);

CREATE INDEX ON accelerator.common_indicator_targets (common_indicator_id);

CREATE TABLE accelerator.auto_calculated_indicator_targets (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    auto_calculated_indicator_id INTEGER NOT NULL REFERENCES accelerator.auto_calculated_indicators ON DELETE CASCADE,
    baseline NUMERIC,
    end_target NUMERIC,

    PRIMARY KEY (project_id, auto_calculated_indicator_id)
);

CREATE INDEX ON accelerator.auto_calculated_indicator_targets (auto_calculated_indicator_id);
