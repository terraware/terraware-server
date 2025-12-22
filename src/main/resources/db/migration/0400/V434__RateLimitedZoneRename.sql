UPDATE rate_limited_events
SET pending_event = replace(
        replace(
                replace(
                        pending_event::text,
                        '"plantingZones"',
                        '"strata"'
                ),
                '"plantingZoneId"',
                '"plantingStratumId"'
        ),
        '"zoneName"',
        '"stratumName"'
                       )::jsonb
WHERE pending_event::text LIKE '%plantingZones%';
