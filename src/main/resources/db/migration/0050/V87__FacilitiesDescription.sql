ALTER TABLE facilities ADD COLUMN description TEXT;
ALTER TABLE facilities DROP COLUMN enabled;
ALTER TABLE facilities DROP COLUMN latitude;
ALTER TABLE facilities DROP COLUMN longitude;
