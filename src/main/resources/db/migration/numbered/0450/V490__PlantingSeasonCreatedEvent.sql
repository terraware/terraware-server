CALL event_log_create_id_index('plantingSeasonId');

INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    seasons.created_by,
    seasons.created_time,
    'com.terraformation.backend.plantingmanagement.event.PlantingSeasonCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'endDate' VALUE seasons.end_date,
        'name' VALUE seasons.name,
        'organizationId' VALUE sites.organization_id,
        'plantingSeasonId' VALUE seasons.id,
        'plantingSiteId' VALUE sites.id,
        'startDate' VALUE seasons.start_date,
        'status' VALUE pss.name
        ABSENT ON NULL
    )::JSONB
FROM tracking.planting_seasons seasons
JOIN tracking.planting_sites sites ON seasons.planting_site_id = sites.id
JOIN tracking.planting_season_statuses pss ON seasons.status_id = pss.id;
