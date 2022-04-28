CREATE TABLE notification_criticalities (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL
);

CREATE TABLE notification_types (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  notification_criticality_id INTEGER NOT NULL REFERENCES notification_criticalities
);

INSERT INTO notification_criticalities (id, name)
VALUES (1, 'Info'),
       (2, 'Warning'),
       (3, 'Error'),
       (4, 'Success');

INSERT INTO notification_types (id, name, notification_criticality_id)
VALUES (1, 'User Added to Organization', 1),
       (2, 'Facility Idle', 2),
       (3, 'Facility Alert Requested', 3)
ON CONFLICT (id) DO UPDATE SET name = excluded.name, notification_criticality_id = excluded.notification_criticality_id;
