INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    alloc.created_by,
    alloc.created_time,
    'com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'organizationId' VALUE sites.organization_id,
        'plantingSeasonId' VALUE seasons.id,
        'plantingSiteId' VALUE sites.id,
        'quantity' VALUE alloc.quantity,
        'speciesId' VALUE alloc.species_id
        ABSENT ON NULL
    )::JSONB
FROM tracking.planting_season_allocated_species alloc
JOIN tracking.planting_seasons seasons ON alloc.planting_season_id = seasons.id
JOIN tracking.planting_sites sites ON seasons.planting_site_id = sites.id;
