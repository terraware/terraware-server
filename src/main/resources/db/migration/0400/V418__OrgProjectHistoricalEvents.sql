INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    created_by,
    created_time,
    'com.terraformation.backend.customer.event.OrganizationCreatedEventV1',
    ('{"organizationId":' || id ||
        ', "name": ' || json_scalar(name) ||
        ', "_historical": true}')::jsonb
FROM organizations;

INSERT INTO event_log (created_by, created_time, event_class, payload)
SELECT
    created_by,
    created_time,
    'com.terraformation.backend.customer.event.ProjectCreatedEventV1',
    ('{"organizationId":' || organization_id ||
     ', "projectId": ' || id ||
     ', "name": ' || json_scalar(name) ||
     ', "_historical": true}')::jsonb
FROM projects;
