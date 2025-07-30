CREATE VIEW accelerator.accelerator_projects AS
SELECT *
FROM (SELECT p.id    as project_id,
             a.id    as application_id,
             part.id as participant_id,
             part.cohort_id,
             EXISTS (SELECT 1
                     FROM organization_internal_tags oit
                     WHERE oit.organization_id = p.organization_id
                       AND oit.internal_tag_id = 4)
                     as has_org_accelerator_tag
      FROM projects p
               LEFT JOIN accelerator.applications a ON p.id = a.project_id
               LEFT JOIN accelerator.participants part ON p.participant_id = part.id
               LEFT JOIN accelerator.project_accelerator_details pad
                         ON p.id = pad.project_id) as accelerator_projects
WHERE application_id IS NOT NULL
   OR participant_id IS NOT NULL
   OR cohort_id IS NOT NULL
   OR has_org_accelerator_tag IS TRUE
;
