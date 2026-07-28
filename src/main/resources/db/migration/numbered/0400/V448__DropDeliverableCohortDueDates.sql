-- Previous version was in V438
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
       COALESCE(project_due_dates.due_date, cohort_modules.end_date) as due_date,
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
                       AND project_due_dates.deliverable_id = deliverables.id;

INSERT INTO accelerator.deliverable_project_due_dates (deliverable_id, project_id, due_date)
SELECT dcdd.deliverable_id, p.id, dcdd.due_date
FROM accelerator.deliverable_cohort_due_dates dcdd
JOIN projects p ON dcdd.cohort_id = p.cohort_id
ON CONFLICT DO NOTHING;

DROP TABLE accelerator.deliverable_cohort_due_dates;
