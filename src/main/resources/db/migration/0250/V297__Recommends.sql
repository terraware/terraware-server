CREATE TABLE variable_section_recommendations (
    section_variable_id BIGINT NOT NULL REFERENCES variables ON DELETE CASCADE,
    section_variable_type_id INTEGER NOT NULL REFERENCES variable_types,
    recommended_variable_id BIGINT NOT NULL REFERENCES variables ON DELETE CASCADE,
    variable_manifest_id BIGINT NOT NULL REFERENCES variable_manifests ON DELETE CASCADE,

    PRIMARY KEY (section_variable_id, recommended_variable_id),
    UNIQUE (recommended_variable_id, variable_manifest_id, section_variable_id),

    CHECK (section_variable_type_id = 8)
);

INSERT INTO variable_section_recommendations
SELECT impacting_variable_id, impacted_variable_id, variable_manifest_id
FROM variable_impacts;

DROP TABLE variable_impacts;

