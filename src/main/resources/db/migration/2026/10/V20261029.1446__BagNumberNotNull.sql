DELETE FROM seedbank.bags WHERE bag_number IS NULL;
ALTER TABLE seedbank.bags ALTER COLUMN bag_number SET NOT NULL;
