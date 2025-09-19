DELETE FROM accelerator.report_system_metrics
    WHERE system_metric_id = 7;

DELETE FROM funder.published_report_system_metrics
    WHERE system_metric_id = 7;

DELETE FROM accelerator.system_metrics
WHERE id = 7;
