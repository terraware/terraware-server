-- Add flags to the tables that are populated based on PerSiteConfig so that if items are deleted
-- from the config but can't be deleted because they're referred to by data in other tables, we can
-- still filter them out of any queries that should ignore deleted configuration.

ALTER TABLE device ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE organization ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE site ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE site_module ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE storage_location ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE;
