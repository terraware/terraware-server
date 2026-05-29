INSERT INTO tracking.planting_seasons
    (planting_site_id, name, start_date, end_date, status_id, created_by, created_time, modified_by, modified_time)
    SELECT
        sps.planting_site_id,
        (ROW_NUMBER() OVER (PARTITION BY sps.planting_site_id ORDER BY sps.start_date))::text AS planting_season_name,
        sps.start_date,
        sps.end_date,
        CASE
            WHEN sps.end_date < local_date THEN 3 -- Past End Date
            WHEN sps.start_date <= local_date THEN 1 -- Active
            ELSE 2 -- Upcoming
        END,
        (select id from users where email = 'system'),
        now(),
        (select id from users where email = 'system'),
        now()
    FROM tracking.simple_planting_seasons sps
    JOIN tracking.planting_sites ps ON ps.id = sps.planting_site_id
    JOIN organizations o ON o.id = ps.organization_id
    CROSS JOIN LATERAL (
        SELECT (now() AT TIME ZONE COALESCE(ps.time_zone, o.time_zone, 'UTC'))::date AS local_date
    ) d
;

DROP TABLE tracking.simple_planting_seasons;
