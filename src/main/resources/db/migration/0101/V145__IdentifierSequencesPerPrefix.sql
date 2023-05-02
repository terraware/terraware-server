DROP TABLE identifier_sequences;

CREATE TABLE identifier_sequences (
    organization_id BIGINT NOT NULL REFERENCES organizations,
    prefix TEXT NOT NULL,
    next_value BIGINT NOT NULL,
    PRIMARY KEY (organization_id, prefix)
);
