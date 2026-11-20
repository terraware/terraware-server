CALL event_log_create_id_index('stratumId');

INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    (SELECT id FROM users WHERE email = 'system'),
    strata.created_time,
    'com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEventV1',
    json_object(
        '_historical' VALUE true,
        'organizationId' VALUE sites.organization_id,
        'plantingSiteId' VALUE sites.id,
        'stratumId' VALUE strata.id,
        'stratumName' VALUE strata.name,
        'source' VALUE 'Terraformation',
        'settings' VALUE jsonb_agg(
            json_object(
                'observationType' VALUE types.name,
                'changedFrom' VALUE '{}'::JSONB,
                'changedTo' VALUE json_object(
                    'mode' VALUE modes.name,
                    'plotPrecisionLevel' VALUE levels.name,
                    'variance' VALUE parameters.variance,
                    'studentsT' VALUE parameters.students_t,
                    'errorMargin' VALUE parameters.error_margin,
                    'numPermanentPlots' VALUE CASE WHEN modes.name = 'Direct' THEN parameters.num_permanent_plots END,
                    'numTemporaryPlots' VALUE CASE WHEN modes.name = 'Direct' THEN parameters.num_temporary_plots END
                    ABSENT ON NULL
                ),
                'numPermanentPlots' VALUE parameters.num_permanent_plots,
                'numTemporaryPlots' VALUE parameters.num_temporary_plots
            )::JSONB ORDER BY parameters.observation_type_id
        )
    )::JSONB
FROM tracking.strata
JOIN tracking.planting_sites sites ON sites.id = strata.planting_site_id
JOIN tracking.stratum_plot_selection_parameters parameters ON parameters.stratum_id = strata.id
JOIN tracking.observation_types types ON types.id = parameters.observation_type_id
JOIN tracking.plot_selection_modes modes ON modes.id = parameters.plot_selection_mode_id
LEFT JOIN tracking.plot_precision_levels levels ON levels.id = parameters.plot_precision_level_id
GROUP BY strata.id, sites.id;
