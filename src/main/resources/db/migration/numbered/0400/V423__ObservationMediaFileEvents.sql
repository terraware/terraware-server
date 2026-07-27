CALL event_log_create_id_index('fileId');
CALL event_log_create_id_index('monitoringPlotId');

INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    f.created_by,
    f.created_time,
    'com.terraformation.backend.tracking.event.ObservationMediaFileUploadedEventV1',
    json_object(
        '_historical' VALUE true,
        'caption' VALUE omf.caption,
        'contentType' VALUE f.content_type,
        'fileId' VALUE f.id,
        'geolocation' VALUE st_asgeojson(f.geolocation)::JSONB,
        'isOriginal' VALUE omf.is_original,
        'monitoringPlotId' VALUE omf.monitoring_plot_id,
        'observationId' VALUE omf.observation_id,
        'organizationId' VALUE ps.organization_id,
        'plantingSiteId' VALUE ps.id,
        'position' VALUE opp.name,
        'type' VALUE omt.name
        ABSENT ON NULL
    )::JSONB
FROM tracking.observation_media_files omf
JOIN tracking.observation_media_types omt ON omf.type_id = omt.id
LEFT JOIN tracking.observation_plot_positions opp ON omf.position_id = opp.id
JOIN tracking.observations o ON omf.observation_id = o.id
JOIN tracking.planting_sites ps ON o.planting_site_id = ps.id
JOIN files f ON omf.file_id = f.id;
