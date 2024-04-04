ALTER TABLE accelerator.project_accelerator_details ADD COLUMN file_naming TEXT;
ALTER TABLE accelerator.project_accelerator_details ADD COLUMN dropbox_folder_path TEXT;
ALTER TABLE accelerator.project_accelerator_details ADD COLUMN google_folder_url TEXT;

INSERT INTO accelerator.project_accelerator_details
    (project_id, file_naming, dropbox_folder_path, google_folder_url)
SELECT pds.project_id,
       COALESCE(pds.file_naming, pad.abbreviated_name),
       pds.dropbox_folder_path,
       pds.google_folder_url
FROM accelerator.project_document_settings pds
LEFT JOIN accelerator.project_accelerator_details pad
    ON pds.project_id = pad.project_id
ON CONFLICT (project_id)
    DO UPDATE
    SET file_naming = EXCLUDED.file_naming,
        dropbox_folder_path = EXCLUDED.dropbox_folder_path,
        google_folder_url = EXCLUDED.google_folder_url;

DROP TABLE accelerator.project_document_settings;

ALTER TABLE accelerator.project_accelerator_details DROP COLUMN abbreviated_name;
