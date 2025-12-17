-- system_metric_id of 5 is mortality rate
DELETE FROM accelerator.report_system_metrics WHERE system_metric_id = 5;
DELETE FROM funder.published_report_system_metrics WHERE system_metric_id = 5;
DELETE FROM accelerator.system_metrics WHERE id = 5;

-- delete project metrics that were setup temporarily for survival rate
DELETE FROM accelerator.report_project_metrics WHERE project_metric_id IN (
    SELECT id FROM accelerator.project_metrics WHERE name LIKE 'Survival rate%'
);
DELETE FROM funder.published_report_project_metrics WHERE project_metric_id IN (
    SELECT id FROM accelerator.project_metrics WHERE name LIKE 'Survival rate%'
);
DELETE FROM accelerator.project_metrics WHERE name LIKE 'Survival rate%';
