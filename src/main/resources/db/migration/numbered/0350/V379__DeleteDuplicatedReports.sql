DELETE FROM accelerator.reports
WHERE id IN (
    SELECT id
    FROM accelerator.reports
    WHERE (config_id, start_date, end_date) IN (
        SELECT config_id, start_date, end_date
        FROM accelerator.reports
        GROUP BY config_id, start_date, end_date
        HAVING COUNT(*) > 1
    ))
AND status_id = 5;
