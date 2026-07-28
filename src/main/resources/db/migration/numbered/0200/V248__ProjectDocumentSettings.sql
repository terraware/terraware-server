CREATE TABLE accelerator.project_document_settings (
    project_id BIGINT PRIMARY KEY REFERENCES projects ON DELETE CASCADE,
    file_naming TEXT NOT NULL,
    google_folder_url TEXT NOT NULL,
    dropbox_folder_path TEXT NOT NULL
);
