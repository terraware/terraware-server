CALL event_log_create_id_index('recordedTreeId');

INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    op.completed_by,
    op.completed_time,
    'com.terraformation.backend.tracking.event.RecordedTreeCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'biomassSpeciesId' VALUE obs.id,
        'description' VALUE rt.description,
        'diameterAtBreastHeightCm' VALUE rt.diameter_at_breast_height_cm,
        'gpsCoordinates' VALUE st_asgeojson(rt.gps_coordinates)::JSONB,
        'heightM' VALUE rt.height_m,
        'isDead' VALUE rt.is_dead,
        'monitoringPlotId' VALUE rt.monitoring_plot_id,
        'observationId' VALUE o.id,
        'organizationId' VALUE ps.organization_id,
        'plantingSiteId' VALUE ps.id,
        'pointOfMeasurementM' VALUE rt.point_of_measurement_m,
        'recordedTreeId' VALUE rt.id,
        'shrubDiameterCm' VALUE rt.shrub_diameter_cm,
        'speciesId' VALUE obs.species_id,
        'speciesName' VALUE obs.scientific_name,
        'treeGrowthForm' VALUE tgf.name,
        'treeNumber' VALUE rt.tree_number,
        'trunkNumber' VALUE rt.trunk_number
        ABSENT ON NULL
    )::JSONB
FROM tracking.recorded_trees rt
JOIN tracking.tree_growth_forms tgf ON rt.tree_growth_form_id = tgf.id
JOIN tracking.observation_biomass_species obs ON rt.biomass_species_id = obs.id
JOIN tracking.observations o ON rt.observation_id = o.id
JOIN tracking.observation_plots op
    ON rt.observation_id = op.observation_id
    AND rt.monitoring_plot_id = op.monitoring_plot_id
JOIN tracking.planting_sites ps ON o.planting_site_id = ps.id;
