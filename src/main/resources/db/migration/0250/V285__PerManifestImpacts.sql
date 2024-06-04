DROP TABLE variable_impacts;

CREATE TABLE variable_impacts (
    impacting_variable_id BIGINT NOT NULL REFERENCES variables ON DELETE CASCADE,
    impacted_variable_id BIGINT NOT NULL REFERENCES variables ON DELETE CASCADE,
    variable_manifest_id BIGINT NOT NULL REFERENCES variable_manifests ON DELETE CASCADE,

    PRIMARY KEY (impacting_variable_id, variable_manifest_id, impacted_variable_id)
);

CREATE UNIQUE INDEX ON variable_impacts (impacted_variable_id, variable_manifest_id, impacting_variable_id);
