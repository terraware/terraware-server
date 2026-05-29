INSERT INTO tracking.planting_seasons
    (planting_site_id, name, start_date, end_date, status_id, created_by, created_time, modified_by, modified_time)
    SELECT
        planting_site_id,
        TO_CHAR(start_date, 'FMMonth FMDD, YYYY') || ' to ' || TO_CHAR(end_date, 'FMMonth FMDD, YYYY') AS planting_season_name,
        start_date,
        end_date,
        CASE WHEN is_active THEN 1 ELSE 3 END,
        (select id from users where email = 'system'),
        now(),
        (select id from users where email = 'system'),
        now()
    FROM tracking.simple_planting_seasons
;

DROP TABLE tracking.simple_planting_seasons;
