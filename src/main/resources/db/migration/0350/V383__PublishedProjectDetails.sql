CREATE TABLE funder.published_project_carbon_certs (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    carbon_certification TEXT NOT NULL,

    PRIMARY KEY (project_id, carbon_certification)
);

CREATE TABLE funder.published_project_land_use (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    land_use_model_type_id INTEGER NOT NULL REFERENCES land_use_model_types,
    land_use_model_hectares NUMERIC,

    PRIMARY KEY (project_id, land_use_model_type_id)
);

CREATE TABLE funder.published_project_sdg (
    project_id BIGINT NOT NULL REFERENCES projects ON DELETE CASCADE,
    sdg_number INTEGER NOT NULL,

    PRIMARY KEY (project_id, sdg_number)
);

CREATE TABLE funder.published_project_details (
    project_id BIGINT PRIMARY KEY NOT NULL REFERENCES projects ON DELETE CASCADE,
    accumulation_rate NUMERIC,
    annual_carbon NUMERIC,
    country_code TEXT references countries,
    deal_description TEXT,
    deal_name TEXT,
    methodology_number TEXT,
    min_project_area NUMERIC,
    num_native_species INTEGER,
    per_hectare_estimated_budget NUMERIC,
    project_area NUMERIC,
    project_highlight_photo_value_id BIGINT REFERENCES docprod.variable_values,
    project_zone_figure_value_id BIGINT REFERENCES docprod.variable_values,
    standard TEXT,
    tf_reforestable_land NUMERIC,
    total_expansion_potential NUMERIC,
    total_vcu NUMERIC,
    verra_link TEXT,
    published_by BIGINT NOT NULL REFERENCES users,
    published_time TIMESTAMP WITH TIME ZONE NOT NULL
);
