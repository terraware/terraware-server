INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    op.completed_by,
    op.completed_time,
    'com.terraformation.backend.tracking.event.BiomassDetailsCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'description' VALUE obd.description,
        'forestType' VALUE bft.name,
        'herbaceousCoverPercent' VALUE obd.herbaceous_cover_percent,
        'monitoringPlotId' VALUE obd.monitoring_plot_id,
        'observationId' VALUE o.id,
        'organizationId' VALUE ps.organization_id,
        'ph' VALUE obd.ph,
        'plantingSiteId' VALUE ps.id,
        'salinityPpt' VALUE obd.salinity_ppt,
        'smallTreesCountHigh' VALUE obd.small_trees_count_high,
        'smallTreesCountLow' VALUE obd.small_trees_count_low,
        'soilAssessment' VALUE obd.soil_assessment,
        'tide' VALUE mt.name,
        'tideTime' VALUE obd.tide_time,
        'waterDepthCm' VALUE obd.water_depth_cm
        ABSENT ON NULL
    )::JSONB
FROM tracking.observation_biomass_details obd
JOIN tracking.biomass_forest_types bft ON obd.forest_type_id = bft.id
LEFT JOIN tracking.mangrove_tides mt ON obd.tide_id = mt.id
JOIN tracking.observations o ON obd.observation_id = o.id
JOIN tracking.observation_plots op
    ON obd.observation_id = op.observation_id
    AND obd.monitoring_plot_id = op.monitoring_plot_id
JOIN tracking.planting_sites ps ON o.planting_site_id = ps.id;
