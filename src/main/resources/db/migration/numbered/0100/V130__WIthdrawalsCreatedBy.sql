ALTER TABLE withdrawals ADD COLUMN created_by BIGINT REFERENCES users;
ALTER TABLE withdrawals ADD COLUMN withdrawn_by BIGINT REFERENCES users;
