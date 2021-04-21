-- Withdrawals for germination testing always have to be associated with germination tests.
ALTER TABLE withdrawal
    ADD COLUMN germination_test_id BIGINT REFERENCES germination_test (id) ON DELETE CASCADE;

ALTER TABLE withdrawal
    ADD CONSTRAINT withdrawal_germination_test_id_unique
    UNIQUE (germination_test_id);

ALTER TABLE withdrawal
    ADD CONSTRAINT germination_testing_withdrawal_has_test_id
        CHECK ((purpose_id <> 7 AND germination_test_id IS NULL) OR
               (purpose_id = 7 AND germination_test_id IS NOT NULL));
