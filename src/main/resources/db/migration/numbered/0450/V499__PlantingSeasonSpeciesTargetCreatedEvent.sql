INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    psst.created_by,
    psst.created_time,
    'com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'organizationId' VALUE sites.organization_id,
        'plantingSeasonId' VALUE seasons.id,
        'plantingSiteId' VALUE sites.id,
        'quantity' VALUE psst.quantity,
        'speciesId' VALUE psst.species_id,
        'stratumName' VALUE str.name,
        'substratumHistoryId' VALUE latest_sub_hist.id,
        'substratumId' VALUE psst.substratum_id,
        'substratumName' VALUE sub.name
        ABSENT ON NULL
    )::JSONB
FROM tracking.planting_season_species_targets psst
JOIN tracking.planting_seasons seasons ON psst.planting_season_id = seasons.id
JOIN tracking.planting_sites sites ON seasons.planting_site_id = sites.id
JOIN tracking.substrata sub ON psst.substratum_id = sub.id
JOIN tracking.strata str ON sub.stratum_id = str.id
JOIN LATERAL (
    SELECT id FROM tracking.substratum_histories
    WHERE substratum_id = psst.substratum_id
    ORDER BY id DESC LIMIT 1
) latest_sub_hist ON true;
