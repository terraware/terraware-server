CREATE VIEW accelerator.accelerator_projects AS
SELECT *
FROM (SELECT p.id    as project_id,
             a.id    as application_id,
             part.id as participant_id,
             part.cohort_id,
             CASE
                 WHEN EXISTS (SELECT 1
                              FROM organization_internal_tags oit
                                       JOIN internal_tags it ON it.id = oit.internal_tag_id
                              WHERE oit.organization_id = o.id
                                AND it.name = 'Accelerator') THEN true
                 ELSE false
                 END as has_org_accelerator_tag
      FROM projects p
               JOIN organizations o ON o.id = p.organization_id
               LEFT JOIN accelerator.applications a ON p.id = a.project_id
               LEFT JOIN accelerator.participants part ON p.participant_id = part.id
               LEFT JOIN accelerator.project_accelerator_details pad
                         ON p.id = pad.project_id) as accelerator_projects
WHERE application_id IS NOT NULL
   OR participant_id IS NOT NULL
   OR COHORT_ID IS NOT NULL
   OR has_org_accelerator_tag IS TRUE
;
