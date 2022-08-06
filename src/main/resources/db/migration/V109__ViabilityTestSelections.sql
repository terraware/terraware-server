-- Each accession can optionally have a single viability test that is considered the "selected" one.
-- There are three ways we could model this: as a "selected test ID" column on the accessions table,
-- as an "is selected" flag on the viability_tests table, or as a separate table. The first two
-- options are more complicated to update when, e.g., the selected test is being deleted or the
-- selection is switching to a newly-inserted test.


-- viability_tests.id is already unique, but we want to use a foreign key to guarantee that we don't
-- mark a test from accession 1 as the selection for accession 2, and the target columns of a
-- foreign key have to have a unique constraint.
ALTER TABLE viability_tests ADD CONSTRAINT viability_tests_id_accession_unique UNIQUE (id, accession_id);

CREATE TABLE viability_test_selections (
    accession_id BIGINT PRIMARY KEY REFERENCES accessions,
    viability_test_id BIGINT NOT NULL,
    UNIQUE (viability_test_id),
    FOREIGN KEY (viability_test_id, accession_id) REFERENCES viability_tests (id, accession_id) ON DELETE CASCADE
);
