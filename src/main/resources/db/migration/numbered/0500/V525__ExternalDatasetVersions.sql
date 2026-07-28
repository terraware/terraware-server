CREATE TABLE external_dataset_types (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE external_dataset_imports (
    external_dataset_type_id INTEGER PRIMARY KEY REFERENCES external_dataset_types,
    imported_time TIMESTAMP WITH TIME ZONE NOT NULL,
    last_publication_date DATE
);
