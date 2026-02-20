-- Previous version was in V448
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
       COALESCE(project_due_dates.due_date, project_modules.end_date) as due_date,
       projects.id as project_id,
       submissions.id as submission_id,
       submissions.submission_status_id,
       submissions.feedback as submission_feedback
FROM accelerator.deliverables deliverables
         JOIN accelerator.modules modules ON deliverables.module_id = modules.id
         JOIN accelerator.project_modules project_modules ON modules.id = project_modules.module_id
         JOIN projects ON project_modules.project_id = projects.id
         LEFT JOIN accelerator.submissions submissions
                   ON submissions.project_id = projects.id
                       AND submissions.deliverable_id = deliverables.id
         LEFT JOIN accelerator.deliverable_project_due_dates project_due_dates
                   ON project_due_dates.project_id = projects.id
                       AND project_due_dates.deliverable_id = deliverables.id;
