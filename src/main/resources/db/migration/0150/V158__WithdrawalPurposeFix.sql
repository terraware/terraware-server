-- A bug allowed creating seed withdrawals that had viability test IDs without the withdrawal
-- purpose being set to "Viability Testing". Fix up the existing withdrawals and add a constraint
-- to prevent it from happening again.

-- Purpose 7 is Viability Testing (can't look it up dynamically in the check constraint).

UPDATE seedbank.withdrawals
SET purpose_id = 7
WHERE purpose_id IS NULL
  AND viability_test_id IS NOT NULL;

ALTER TABLE seedbank.withdrawals
    ADD CONSTRAINT withdrawals_test_id_requires_purpose
        CHECK ((viability_test_id IS NULL AND (purpose_id IS NULL OR purpose_id <> 7)) OR
               (viability_test_id IS NOT NULL AND purpose_id IS NOT NULL AND purpose_id = 7));
