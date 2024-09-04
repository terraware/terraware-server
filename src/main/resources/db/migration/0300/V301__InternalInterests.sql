CREATE TABLE accelerator.internal_interests (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    UNIQUE (name)
);
