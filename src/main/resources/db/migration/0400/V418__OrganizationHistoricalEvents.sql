INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    created_by,
    created_time,
    'com.terraformation.backend.customer.event.OrganizationCreatedEventV2',
    ('{"organizationId":' || id ||
        ', "name": ' || json_scalar(name) ||
        ', "_historical": true}')::jsonb
FROM organizations;
