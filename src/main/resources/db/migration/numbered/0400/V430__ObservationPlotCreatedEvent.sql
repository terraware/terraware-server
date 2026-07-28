INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    op.created_by,
    op.created_time,
    'com.terraformation.backend.tracking.event.ObservationPlotCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'isPermanent' VALUE op.is_permanent,
        'monitoringPlotHistoryId' VALUE op.monitoring_plot_history_id,
        'monitoringPlotId' VALUE op.monitoring_plot_id,
        'observationId' VALUE op.observation_id,
        'organizationId' VALUE ps.organization_id,
        'plantingSiteId' VALUE ps.id,
        'plotNumber' VALUE mp.plot_number
        ABSENT ON NULL
    )::JSONB
FROM tracking.observation_plots op
JOIN tracking.monitoring_plots mp ON op.monitoring_plot_id = mp.id
JOIN tracking.planting_sites ps ON mp.planting_site_id = ps.id;
