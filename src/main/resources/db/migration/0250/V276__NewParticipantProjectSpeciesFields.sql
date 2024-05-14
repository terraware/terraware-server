ALTER TABLE accelerator.participant_project_species ADD COLUMN created_by BIGINT REFERENCES users (id);
ALTER TABLE accelerator.participant_project_species ADD COLUMN modified_by BIGINT REFERENCES users (id);
UPDATE accelerator.participant_project_species SET (created_by, modified_by) = (
    SELECT organizations.created_by, organizations.modified_by
    FROM organizations
    LEFT JOIN species on species.organization_id = organizations.id
    WHERE species.id = accelerator.participant_project_species.species_id
);

ALTER TABLE accelerator.participant_project_species ADD COLUMN created_time TIMESTAMP WITH TIME ZONE;
ALTER TABLE accelerator.participant_project_species ADD COLUMN modified_time TIMESTAMP WITH TIME ZONE;

UPDATE accelerator.participant_project_species SET created_time = NOW();
UPDATE accelerator.participant_project_species SET modified_time = NOW();
ALTER TABLE accelerator.participant_project_species ALTER COLUMN created_time SET NOT NULL;
ALTER TABLE accelerator.participant_project_species ALTER COLUMN modified_time SET NOT NULL;
