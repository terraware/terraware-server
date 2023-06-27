CREATE TABLE conservation_categories (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL UNIQUE
);

ALTER TABLE species ADD COLUMN conservation_category_id TEXT REFERENCES conservation_categories (id);
