ALTER TABLE accelerator.project_accelerator_details ADD COLUMN deal_name TEXT UNIQUE;

WITH DealNames AS (
    SELECT
        projects.id AS project_id,
        applications.internal_name AS application_internal_name,
        participants.name AS participant_name,
        ROW_NUMBER() OVER (PARTITION BY participants.name ORDER BY projects.id) AS participant_suffix
    FROM projects projects
    LEFT JOIN accelerator.participants participants
    ON projects.participant_id = participants.id
    LEFT JOIN accelerator.cohorts cohorts
    ON participants.cohort_id = cohorts.id
    LEFT JOIN accelerator.applications applications
    ON projects.id = applications.project_id
    WHERE applications.id IS NOT NULL OR cohorts.id IS NOT NULL
)
INSERT INTO accelerator.project_accelerator_details (project_id, deal_name)
SELECT
    dn.project_id,
    CASE
        WHEN (dn.participant_name IS NOT NULL) AND (dn.participant_suffix = 1)
            THEN dn.participant_name
        WHEN (dn.participant_name IS NOT NULL) AND (dn.participant_suffix > 1)
            THEN CONCAT(dn.participant_name, '_', dn.participant_suffix)
        ELSE application_internal_name
    END AS deal_name
FROM DealNames dn;

ALTER TABLE accelerator.project_accelerator_details ALTER COLUMN deal_name SET NOT NULL;
