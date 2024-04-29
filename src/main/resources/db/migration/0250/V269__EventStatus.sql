CREATE TABLE accelerator.event_statuses (
     id INTEGER PRIMARY KEY,
     name TEXT NOT NULL,
     UNIQUE (name)
);
