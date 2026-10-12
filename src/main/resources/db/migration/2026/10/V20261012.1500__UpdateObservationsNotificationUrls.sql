-- Links to a planting site's observation list used to be /observations/{plantingSiteId}, which is
-- the wrong format; the correct one is /observations?plantingSiteId={plantingSiteId}.
UPDATE notifications
SET local_url = regexp_replace(
        local_url,
        '^/observations/(\d+)\?organizationId=(\d+)$',
        '/observations?organizationId=\2&plantingSiteId=\1')
WHERE local_url ~ '^/observations/\d+\?organizationId=\d+$';
