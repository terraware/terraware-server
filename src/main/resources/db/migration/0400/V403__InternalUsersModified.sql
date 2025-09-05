ALTER TABLE project_internal_users
    ADD COLUMN created_time TIMESTAMP WITH TIME ZONE DEFAULT NOW();
ALTER TABLE project_internal_users
    ADD COLUMN modified_time TIMESTAMP WITH TIME ZONE DEFAULT NOW();

ALTER TABLE project_internal_users
    ADD COLUMN created_by BIGINT REFERENCES users;
ALTER TABLE project_internal_users
    ADD COLUMN modified_by BIGINT REFERENCES users;

-- Most internal users have been Project Leads, which includes TF Contacts
UPDATE project_internal_users piu
SET (created_by, modified_by, created_time, modified_time) = (
    SELECT ou.created_by, ou.modified_by, ou.created_time, ou.modified_time
    FROM organization_users ou
    WHERE ou.user_id = piu.user_id
    AND ou.organization_id = (select organization_id from projects where id = piu.project_id)
    AND ou.role_id = 5 -- TF CONTACT
)
WHERE piu.project_internal_role_id in (1, 2) -- only do this for users that became TF Contacts through internal roles
;

-- If any others remain, use system user
UPDATE project_internal_users piu
SET (created_by, modified_by, created_time, modified_time) = (
    SELECT u.id, u.id, NOW(), NOW()
    FROM users u
    WHERE u.user_type_id = 4 -- system user
)
WHERE created_by IS NULL;

ALTER TABLE project_internal_users
    ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE project_internal_users
    ALTER COLUMN modified_by SET NOT NULL;
ALTER TABLE project_internal_users
    ALTER COLUMN created_time SET NOT NULL;
ALTER TABLE project_internal_users
    ALTER COLUMN modified_time SET NOT NULL;
