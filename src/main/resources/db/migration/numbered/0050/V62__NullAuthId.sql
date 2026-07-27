-- Allow users to be inserted without an auth_id, so we can hold information about people who
-- haven't gone through registration yet.
ALTER TABLE users ALTER COLUMN auth_id DROP NOT NULL;
