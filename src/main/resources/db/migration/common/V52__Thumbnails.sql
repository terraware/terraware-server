ALTER TABLE thumbnail RENAME TO thumbnails;

ALTER TABLE thumbnails ADD COLUMN content_type TEXT NOT NULL;
ALTER TABLE thumbnails ADD COLUMN created_time TIMESTAMP WITH TIME ZONE NOT NULL;
ALTER TABLE thumbnails ADD COLUMN size INTEGER NOT NULL;
ALTER TABLE thumbnails ADD COLUMN storage_url TEXT NOT NULL;

ALTER TABLE thumbnails DROP COLUMN file_name;

-- Clients may want to specify thumbnail sizes by either width or height depending on their layout
-- requirements. We only want one thumbnail of a particular size for each photo.
ALTER TABLE thumbnails ADD CONSTRAINT thumbnails_unique_width UNIQUE (photo_id, width);
ALTER TABLE thumbnails ADD CONSTRAINT thumbnails_unique_height UNIQUE (photo_id, height);

-- A file in the file store should never be described by multiple rows in the thumbnails table.
ALTER TABLE thumbnails ADD CONSTRAINT thumbnails_unique_storage_url UNIQUE (storage_url);
