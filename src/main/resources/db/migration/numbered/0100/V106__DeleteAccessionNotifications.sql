-- Delete notifications for automated accession workflow steps that are shortly going to be
-- simplified and turned into manual status updates.
DELETE FROM notifications WHERE notification_type_id IN (5, 7, 8, 9, 10, 11);
DELETE FROM notification_types WHERE id IN (5, 7, 8, 9, 10, 11);
