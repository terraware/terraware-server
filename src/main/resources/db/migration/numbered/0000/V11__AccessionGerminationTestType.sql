CREATE TABLE accession_germination_test_type (
    accession_id BIGINT NOT NULL REFERENCES accession (id),
    germination_test_type_id INTEGER NOT NULL REFERENCES germination_test_type (id),
    PRIMARY KEY (accession_id, germination_test_type_id)
);

COMMENT ON TABLE accession_germination_test_type
    IS 'Which germination test types are enabled for a given accession.';
