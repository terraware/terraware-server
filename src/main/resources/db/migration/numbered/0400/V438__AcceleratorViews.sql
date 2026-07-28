-- No change to this view, but it depends on accelerator_projects and we can't replace that view
-- while this one exists.
DROP VIEW accelerator.project_variables;

-- Replace participant_id not-null condition with phase_id; remove participant and cohort IDs
-- from results since they weren't used by anything.
DROP VIEW accelerator.accelerator_projects;
CREATE VIEW accelerator.accelerator_projects AS
SELECT project_id,
       application_id,
       has_org_accelerator_tag
FROM (
    SELECT p.id AS project_id,
           a.id AS application_id,
           p.phase_id,
           (EXISTS ( SELECT 1
                     FROM organization_internal_tags oit
                     WHERE oit.organization_id = p.organization_id
                     AND oit.internal_tag_id = 4)
               ) AS has_org_accelerator_tag
    FROM projects p
        LEFT JOIN accelerator.applications a
            ON p.id = a.project_id
        LEFT JOIN accelerator.project_accelerator_details pad
            ON p.id = pad.project_id) accelerator_projects
WHERE application_id IS NOT NULL
OR phase_id IS NOT NULL
OR has_org_accelerator_tag IS TRUE;

CREATE OR REPLACE VIEW accelerator.project_deliverables AS
SELECT deliverables.id as deliverable_id,
       deliverables.deliverable_category_id,
       deliverables.deliverable_type_id,
       deliverables.position,
       deliverables.name,
       deliverables.description_html,
       deliverables.is_required,
       deliverables.is_sensitive,
       deliverables.module_id,
       COALESCE(project_due_dates.due_date, cohort_due_dates.due_date, cohort_modules.end_date) as due_date,
       projects.id as project_id,
       submissions.id as submission_id,
       submissions.submission_status_id,
       submissions.feedback as submission_feedback
FROM accelerator.deliverables deliverables
         JOIN accelerator.modules modules ON deliverables.module_id = modules.id
         JOIN accelerator.cohort_modules cohort_modules ON modules.id = cohort_modules.module_id
         JOIN accelerator.cohorts cohorts ON cohorts.id = cohort_modules.cohort_id
         JOIN projects ON projects.cohort_id = cohorts.id
         LEFT JOIN accelerator.submissions submissions
                   ON submissions.project_id = projects.id
                       AND submissions.deliverable_id = deliverables.id
         LEFT JOIN accelerator.deliverable_project_due_dates project_due_dates
                   ON project_due_dates.project_id = projects.id
                       AND project_due_dates.deliverable_id = deliverables.id
         LEFT JOIN accelerator.deliverable_cohort_due_dates cohort_due_dates
                   ON cohort_due_dates.cohort_id = cohorts.id
                       AND cohort_due_dates.deliverable_id = deliverables.id;

CREATE OR REPLACE VIEW accelerator.project_variables AS
WITH latest_variables AS (SELECT stable_id,
                                 MAX(id) as variable_id
                          FROM docprod.variables
                          GROUP BY stable_id),
     project_variables AS (SELECT DISTINCT project_id,
                                           lv.variable_id
                           FROM docprod.variable_values vv
                                    JOIN latest_variables lv ON lv.variable_id = vv.variable_id)
SELECT ap.project_id,
       lv.stable_id,
       lv.variable_id,
       v.name,
       v.is_list,
       v.variable_type_id,
       vs.is_multiple as is_multi_select
FROM accelerator.accelerator_projects ap
         LEFT JOIN project_variables pv ON pv.project_id = ap.project_id
         LEFT JOIN latest_variables lv ON lv.variable_id = pv.variable_id
         LEFT JOIN docprod.variables v ON v.id = lv.variable_id
         LEFT JOIN docprod.variable_selects vs ON vs.variable_id = lv.variable_id;
