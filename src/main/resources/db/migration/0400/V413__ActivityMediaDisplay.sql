ALTER TABLE accelerator.activity_media_files
    ADD COLUMN is_hidden_on_map BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN list_position INTEGER,
    ADD CONSTRAINT unambiguous_list_order
        UNIQUE (activity_id, list_position)
            DEFERRABLE
            INITIALLY DEFERRED;

-- Put existing files in file ID order by default.
WITH media_positions AS (
    SELECT file_id, RANK() OVER (PARTITION BY activity_id ORDER BY file_id) AS list_position
    FROM accelerator.activity_media_files
)
UPDATE accelerator.activity_media_files amf
SET list_position = mp.list_position
FROM media_positions mp
WHERE amf.file_id = mp.file_id;

ALTER TABLE accelerator.activity_media_files
    ALTER COLUMN list_position SET NOT NULL;
