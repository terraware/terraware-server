CREATE VIEW active_account AS
    SELECT *
    FROM account
    WHERE NOT deleted;
COMMENT ON VIEW active_account
    IS 'Accounts have not been marked as deleted; use this rather than the account table for most queries to reduce the chance of forgetting to filter on the deleted flag.';

CREATE VIEW latest_timeseries_value AS
    SELECT timeseries_id, created_time, value
    FROM (
             SELECT timeseries_id,
                    created_time,
                    value,
                    ROW_NUMBER()
                    OVER (PARTITION BY timeseries_id ORDER BY created_time DESC) AS row_num
             FROM timeseries_value) AS ordered
    WHERE row_num = 1;
