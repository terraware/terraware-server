INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    withdrawals.created_by,
    withdrawals.created_time,
    'com.terraformation.backend.plantingmanagement.event.PlantingSeasonWithdrawalCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'organizationId' VALUE sites.organization_id,
        'plantingSeasonId' VALUE seasons.id,
        'plantingSiteId' VALUE sites.id,
        'withdrawalId' VALUE withdrawals.id
        ABSENT ON NULL
    )::JSONB
FROM nursery.withdrawals withdrawals
JOIN tracking.planting_seasons seasons ON withdrawals.planting_season_id = seasons.id
JOIN tracking.planting_sites sites ON seasons.planting_site_id = sites.id
WHERE withdrawals.planting_season_id IS NOT NULL;
