INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    pdr.created_by,
    pdr.created_time,
    'com.terraformation.backend.plantingmanagement.event.PlantingDateRequestCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'date' VALUE pdr.date,
        'notes' VALUE pdr.notes,
        'organizationId' VALUE sites.organization_id,
        'plantingSeasonId' VALUE seasons.id,
        'plantingSiteId' VALUE sites.id,
        'scheduledPlantingDateId' VALUE pdr.scheduled_planting_date_id,
        'status' VALUE statuses.name
        ABSENT ON NULL
    )::JSONB
FROM tracking.planting_date_requests pdr
JOIN tracking.scheduled_planting_dates spd ON pdr.scheduled_planting_date_id = spd.id
JOIN tracking.planting_seasons seasons ON spd.planting_season_id = seasons.id
JOIN tracking.planting_sites sites ON seasons.planting_site_id = sites.id
JOIN tracking.planting_date_request_statuses statuses ON pdr.status_id = statuses.id;

INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    pdr.created_by,
    pdr.created_time,
    'com.terraformation.backend.plantingmanagement.event.PlantingDateRequestSpeciesCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'organizationId' VALUE sites.organization_id,
        'plantingSeasonId' VALUE seasons.id,
        'plantingSiteId' VALUE sites.id,
        'quantity' VALUE pdrs.quantity,
        'scheduledPlantingDateId' VALUE pdr.scheduled_planting_date_id,
        'speciesId' VALUE pdrs.species_id,
        'stratumName' VALUE str.name,
        'substratumHistoryId' VALUE latest_sub_hist.id,
        'substratumId' VALUE pdrs.substratum_id,
        'substratumName' VALUE sub.name
        ABSENT ON NULL
    )::JSONB
FROM tracking.planting_date_request_species pdrs
JOIN tracking.planting_date_requests pdr
    ON pdrs.scheduled_planting_date_id = pdr.scheduled_planting_date_id
JOIN tracking.scheduled_planting_dates spd ON pdr.scheduled_planting_date_id = spd.id
JOIN tracking.planting_seasons seasons ON spd.planting_season_id = seasons.id
JOIN tracking.planting_sites sites ON seasons.planting_site_id = sites.id
JOIN tracking.substrata sub ON pdrs.substratum_id = sub.id
JOIN tracking.strata str ON sub.stratum_id = str.id
JOIN LATERAL (
    SELECT id FROM tracking.substratum_histories
    WHERE substratum_id = pdrs.substratum_id
    ORDER BY id DESC LIMIT 1
) latest_sub_hist ON true;
