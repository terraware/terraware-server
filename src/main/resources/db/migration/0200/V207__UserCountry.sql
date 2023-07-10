ALTER TABLE users ADD COLUMN country_code TEXT;
ALTER TABLE users ADD FOREIGN KEY (country_code) REFERENCES countries (code);
