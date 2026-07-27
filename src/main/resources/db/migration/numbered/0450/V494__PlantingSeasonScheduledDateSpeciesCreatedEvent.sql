CALL event_log_create_id_index('substratumId');

INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    spd.created_by,
    spd.created_time,
    'com.terraformation.backend.plantingmanagement.event.PlantingSeasonScheduledDateSpeciesCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'organizationId' VALUE sites.organization_id,
        'plantingSeasonId' VALUE seasons.id,
        'plantingSiteId' VALUE sites.id,
        'quantity' VALUE spds.quantity,
        'scheduledPlantingDateId' VALUE spd.id,
        'speciesId' VALUE spds.species_id,
        'stratumName' VALUE str.name,
        'substratumHistoryId' VALUE latest_sub_hist.id,
        'substratumId' VALUE spds.substratum_id,
        'substratumName' VALUE sub.name
        ABSENT ON NULL
    )::JSONB
FROM tracking.scheduled_planting_date_species spds
JOIN tracking.scheduled_planting_dates spd ON spds.scheduled_planting_date_id = spd.id
JOIN tracking.planting_seasons seasons ON spd.planting_season_id = seasons.id
JOIN tracking.planting_sites sites ON seasons.planting_site_id = sites.id
JOIN tracking.substrata sub ON spds.substratum_id = sub.id
JOIN tracking.strata str ON sub.stratum_id = str.id
JOIN LATERAL (
    SELECT id FROM tracking.substratum_histories
    WHERE substratum_id = spds.substratum_id
    ORDER BY id DESC LIMIT 1
) latest_sub_hist ON true;
