DROP TABLE sites;
DROP TABLE project_type_selections;
DROP TABLE project_users;
DROP TABLE projects;
DROP TABLE project_statuses;
DROP TABLE project_types;

-- Delete "user added to project" notifications
DELETE FROM notifications WHERE notification_type_id = 4;
DELETE FROM notification_types WHERE id = 4;
