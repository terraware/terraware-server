-- We want to be able to rely on quadrat details (and thus the quadrat creation event) always
-- existing if there's any quadrat-level information.
ALTER TABLE tracking.observation_biomass_quadrat_species
    ADD CONSTRAINT quadrat_species_requires_quadrat_details
        FOREIGN KEY (observation_id, monitoring_plot_id, position_id)
            REFERENCES tracking.observation_biomass_quadrat_details
                (observation_id, monitoring_plot_id, position_id)
            ON DELETE CASCADE;

INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    op.completed_by,
    op.completed_time,
    'com.terraformation.backend.tracking.event.BiomassQuadratCreatedEventV1',
    json_object(
        '_historical' VALUE true,
        'description' VALUE obqd.description,
        'monitoringPlotId' VALUE obqd.monitoring_plot_id,
        'observationId' VALUE o.id,
        'organizationId' VALUE ps.organization_id,
        'plantingSiteId' VALUE ps.id,
        'position' VALUE opp.name
        ABSENT ON NULL
    )::JSONB
FROM tracking.observation_biomass_quadrat_details obqd
JOIN tracking.observation_plot_positions opp ON obqd.position_id = opp.id
JOIN tracking.observations o ON obqd.observation_id = o.id
JOIN tracking.observation_plots op
    ON obqd.observation_id = op.observation_id
    AND obqd.monitoring_plot_id = op.monitoring_plot_id
JOIN tracking.planting_sites ps ON o.planting_site_id = ps.id;
