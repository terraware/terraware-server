ALTER TABLE nursery.withdrawals DROP COLUMN destination;
ALTER TABLE nursery.withdrawals RENAME COLUMN reason TO notes;
