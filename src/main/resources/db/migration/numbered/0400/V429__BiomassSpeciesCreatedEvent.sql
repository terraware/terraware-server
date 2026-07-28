CALL event_log_create_id_index('biomassSpeciesId');
CALL event_log_create_id_index('speciesId');

INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    op.completed_by,
    op.completed_time,
    'com.terraformation.backend.tracking.event.BiomassSpeciesCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'biomassSpeciesId' VALUE obs.id,
        'commonName' VALUE obs.common_name,
        'isInvasive' VALUE obs.is_invasive,
        'isThreatened' VALUE obs.is_threatened,
        'monitoringPlotId' VALUE obs.monitoring_plot_id,
        'observationId' VALUE o.id,
        'organizationId' VALUE ps.organization_id,
        'plantingSiteId' VALUE ps.id,
        'scientificName' VALUE obs.scientific_name,
        'speciesId' VALUE obs.species_id
        ABSENT ON NULL
    )::JSONB
FROM tracking.observation_biomass_species obs
JOIN tracking.observations o ON obs.observation_id = o.id
JOIN tracking.observation_plots op
    ON obs.observation_id = op.observation_id
    AND obs.monitoring_plot_id = op.monitoring_plot_id
JOIN tracking.planting_sites ps ON o.planting_site_id = ps.id;
