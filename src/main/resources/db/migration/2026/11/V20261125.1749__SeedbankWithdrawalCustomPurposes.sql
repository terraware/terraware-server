ALTER TABLE seedbank.withdrawals
    ADD COLUMN custom_purpose_id BIGINT REFERENCES withdrawal_custom_purposes ON DELETE SET NULL,
    ADD CONSTRAINT custom_purpose_only_for_other CHECK (custom_purpose_id IS NULL OR purpose_id = 6);
