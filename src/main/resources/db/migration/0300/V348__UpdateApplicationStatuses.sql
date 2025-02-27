INSERT INTO accelerator.application_statuses (id, name)
VALUES (1, 'Not Submitted'),
       (2, 'Failed Pre-screen'),
       (3, 'Passed Pre-screen'),
       (4, 'Submitted'),
       (5, 'Sourcing Team Review'), -- Renamed from 'PL Review'
       (6, 'GIS Assessment'), -- Renamed from 'Ready for Review'
       (7, 'Expert Review'), -- Combined 'Pre-check', 'Needs Follow-up'
       (8, 'Carbon Assessment'), -- New status.
       (9, 'P0 Eligible'), -- Renamed from 'Carbon Eligible'
       (10, 'Accepted'),
       (11, 'Issue Reassessment'), -- Combined 'Issue Active', 'Issue Pending', 'Issue Resolved'
       (12, 'Not Eligible') -- Renamed from 'Not Accepted'
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

UPDATE accelerator.applications
SET application_status_id = CASE
    -- 7 -> 7, 'Pre-check' -> 'Expert Review'
    WHEN application_status_id = 8 THEN 7 -- 'Needs Follow-up' -> 'Expert Review'
    -- 11 -> 11, 'Issue Active' -> 'Issue Reassessment'
    WHEN application_status_id = 12 THEN 11 -- 'Issue Pending' -> 'Issue Reassessment'
    WHEN application_status_id = 13 THEN 11 -- 'Issue Resolved' -> 'Issue Reassessment'
    WHEN application_status_id = 14 THEN 12 -- 'Not Accepted' -> 'Not Eligible'
    ELSE application_status_id
END;

UPDATE accelerator.application_histories
    SET application_status_id = CASE
    -- 7 -> 7, 'Pre-check' -> 'Expert Review'
    WHEN application_status_id = 8 THEN 7 -- 'Needs Follow-up' -> 'Expert Review'
    -- 11 -> 11, 'Issue Active' -> 'Issue Reassessment'
    WHEN application_status_id = 12 THEN 11 -- 'Issue Pending' -> 'Issue Reassessment'
    WHEN application_status_id = 13 THEN 11 -- 'Issue Resolved' -> 'Issue Reassessment'
    WHEN application_status_id = 14 THEN 12 -- 'Not Accepted' -> 'Not Eligible'
    ELSE application_status_id
END;

DELETE FROM accelerator.application_statuses
WHERE id = 13 OR id = 14;
