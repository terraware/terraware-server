CREATE TABLE land_use_model_types (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE accelerator.pipelines (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

CREATE TABLE accelerator.deal_stages (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    pipeline_id INTEGER NOT NULL REFERENCES accelerator.pipelines
);

CREATE TABLE project_land_use_model_types (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    land_use_model_type_id INTEGER NOT NULL REFERENCES land_use_model_types,

    PRIMARY KEY (project_id, land_use_model_type_id)
);

CREATE TABLE accelerator.project_accelerator_details (
    project_id BIGINT PRIMARY KEY REFERENCES projects,
    pipeline_id INTEGER REFERENCES accelerator.pipelines,
    deal_stage_id INTEGER REFERENCES accelerator.deal_stages,
    application_reforestable_land NUMERIC,
    confirmed_reforestable_land NUMERIC,
    total_expansion_potential NUMERIC,
    num_native_species INTEGER,
    min_carbon_accumulation NUMERIC,
    max_carbon_accumulation NUMERIC,
    per_hectare_budget NUMERIC,
    num_communities INTEGER,
    investment_thesis TEXT,
    failure_risk TEXT,
    what_needs_to_be_true TEXT,
    deal_description TEXT
);

ALTER TABLE projects ADD COLUMN country_code TEXT REFERENCES countries (code);
