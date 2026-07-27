ALTER TABLE facilities ADD COLUMN created_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE facilities ADD COLUMN modified_time TIMESTAMP WITH TIME ZONE;
UPDATE facilities SET created_time = NOW(), modified_time = NOW() WHERE created_time IS NULL;
ALTER TABLE facilities ALTER COLUMN created_time SET NOT NULL;
ALTER TABLE facilities ALTER COLUMN modified_time SET NOT NULL;
