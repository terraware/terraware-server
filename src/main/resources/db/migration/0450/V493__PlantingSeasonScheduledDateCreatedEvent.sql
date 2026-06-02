CALL event_log_create_id_index('scheduledPlantingDateId');

INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    spd.created_by,
    spd.created_time,
    'com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDateCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'date' VALUE spd.date,
        'organizationId' VALUE sites.organization_id,
        'plantingSeasonId' VALUE seasons.id,
        'plantingSiteId' VALUE sites.id,
        'scheduledPlantingDateId' VALUE spd.id
        ABSENT ON NULL
    )::JSONB
FROM tracking.scheduled_planting_dates spd
JOIN tracking.planting_seasons seasons ON spd.planting_season_id = seasons.id
JOIN tracking.planting_sites sites ON seasons.planting_site_id = sites.id;
