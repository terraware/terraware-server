ALTER TABLE uploads ADD COLUMN locale TEXT;
UPDATE uploads SET locale = 'en' WHERE locale IS NULL;
ALTER TABLE uploads ALTER COLUMN locale SET NOT NULL;
