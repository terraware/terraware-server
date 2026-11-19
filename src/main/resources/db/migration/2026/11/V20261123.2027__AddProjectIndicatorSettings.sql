CREATE TABLE accelerator.project_common_indicators
(
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    common_indicator_id BIGINT NOT NULL REFERENCES accelerator.common_indicators ON DELETE CASCADE,
    ref_id TEXT COLLATE natural_numeric,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    is_publishable BOOLEAN NOT NULL DEFAULT FALSE,
    notes TEXT,
    primary_data_source TEXT,
    PRIMARY KEY (project_id, common_indicator_id)
);

CREATE INDEX ON accelerator.project_common_indicators (common_indicator_id);

CREATE TABLE accelerator.project_auto_calculated_indicators
(
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    auto_calculated_indicator_id INTEGER NOT NULL REFERENCES accelerator.auto_calculated_indicators ON DELETE CASCADE,
    ref_id TEXT COLLATE natural_numeric,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    is_publishable BOOLEAN NOT NULL DEFAULT FALSE,
    notes TEXT,
    primary_data_source TEXT,
    PRIMARY KEY (project_id, auto_calculated_indicator_id)
);

CREATE INDEX ON accelerator.project_auto_calculated_indicators (auto_calculated_indicator_id);

INSERT INTO accelerator.project_common_indicators
    (project_id, common_indicator_id, ref_id, active, is_publishable, notes, primary_data_source)
SELECT projects.id, indicators.id, indicators.ref_id, indicators.active, indicators.is_publishable,
       indicators.notes, indicators.primary_data_source
FROM projects
         CROSS JOIN accelerator.common_indicators indicators
WHERE EXISTS (SELECT 1 FROM accelerator.project_report_configs configs WHERE configs.project_id = projects.id)
ON CONFLICT DO NOTHING;

INSERT INTO accelerator.project_auto_calculated_indicators
    (project_id, auto_calculated_indicator_id, ref_id, active, is_publishable, notes, primary_data_source)
SELECT projects.id, indicators.id, indicators.ref_id, indicators.active, indicators.is_publishable,
       indicators.notes, indicators.primary_data_source
FROM projects
         CROSS JOIN accelerator.auto_calculated_indicators indicators
WHERE EXISTS (SELECT 1 FROM accelerator.project_report_configs configs WHERE configs.project_id = projects.id)
ON CONFLICT DO NOTHING;
