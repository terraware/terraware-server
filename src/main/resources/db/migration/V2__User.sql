CREATE TABLE organization (
    id   INTEGER PRIMARY KEY,
    name TEXT NOT NULL
);

COMMENT ON COLUMN organization.id
    IS 'Assigned centrally; matches the organization ID on the cloud service.';

CREATE TABLE "user" (
    id                 BIGSERIAL PRIMARY KEY,
    email_address      TEXT NOT NULL,
    password_hash      TEXT NOT NULL,
    full_name          TEXT NOT NULL,
    organization_id    INTEGER NOT NULL REFERENCES organization,
    organization_admin BOOLEAN DEFAULT false NOT NULL,
    super_admin        BOOLEAN DEFAULT false NOT NULL,
    deleted            BOOLEAN DEFAULT false NOT NULL,

    CONSTRAINT user_email_address_excl EXCLUDE (email_address WITH =) WHERE (NOT deleted)
);

COMMENT ON CONSTRAINT user_email_address_excl ON "user"
    IS 'Email address must be unique for active (non-deleted) users.';

CREATE VIEW active_user AS
    SELECT *
    FROM "user"
    WHERE NOT deleted;
COMMENT ON VIEW active_user
    IS 'Users whose accounts have not been marked as deleted; use this rather than the user table for most queries to reduce the chance of forgetting to filter on the deleted flag.';

CREATE TABLE key (
    hash                 TEXT NOT NULL PRIMARY KEY,
    key_part             TEXT NOT NULL,
    organization_id      INTEGER NOT NULL REFERENCES organization,
    creation_timestamp   TIMESTAMP WITH TIME ZONE NOT NULL,
    revocation_timestamp TIMESTAMP WITH TIME ZONE
);
