ALTER TABLE accelerator.participant_project_species ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE accelerator.participant_project_species ADD COLUMN created_time TIMESTAMP WITH TIME ZONE NOT NULL;
ALTER TABLE accelerator.participant_project_species ADD COLUMN modified_by BIGINT REFERENCES users (id);
ALTER TABLE accelerator.participant_project_species ADD COLUMN modified_time TIMESTAMP WITH TIME ZONE NOT NULL;
