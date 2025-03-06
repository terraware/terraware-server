INSERT INTO accelerator.application_statuses (id, name)
VALUES (1, 'Not Submitted'),
       (2, 'Failed Pre-screen'),
       (3, 'Passed Pre-screen'),
       (4, 'Submitted'),
       (5, 'Sourcing Team Review'),
       (6, 'GIS Assessment'),
       (7, 'Expert Review'),
       (8, 'Carbon Assessment'),
       (9, 'P0 Eligible'),
       (10, 'Accepted'),
       (11, 'Issue Active'),
       (12, 'Issue Reassessment'),
       (13, 'Not Eligible')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

UPDATE accelerator.applications
SET application_status_id = 13
WHERE application_status_id = 12;

UPDATE accelerator.application_histories
SET application_status_id = 13
WHERE application_status_id = 12;
