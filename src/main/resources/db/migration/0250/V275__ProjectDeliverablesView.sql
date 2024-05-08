-- View for projects to find deliverables, due dates and their submission statuses
CREATE VIEW accelerator.project_deliverables AS
SELECT deliverables.id as deliverable_id,
       deliverables.deliverable_category_id,
       deliverables.deliverable_type_id,
       deliverables.position,
       deliverables.name,
       deliverables.description_html,
       deliverables.is_required,
       deliverables.is_sensitive,
       deliverables.module_id,
       cohort_modules.end_date as due_date,
       projects.id as project_id,
       submissions.id as submission_id,
       submissions.submission_status_id,
       submissions.feedback as submission_feedback
FROM accelerator.deliverables deliverables
    JOIN accelerator.modules modules ON deliverables.module_id = modules.id
    JOIN accelerator.cohort_modules cohort_modules ON modules.id = cohort_modules.module_id
    JOIN accelerator.cohorts cohorts ON cohorts.id = cohort_modules.cohort_id
    JOIN accelerator.participants participants ON participants.cohort_id = cohorts.id
    JOIN projects ON projects.participant_id = participants.id
    LEFT JOIN accelerator.submissions submissions ON submissions.project_id = projects.id
                                                   AND submissions.deliverable_id = deliverables.id;
