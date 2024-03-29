CREATE TABLE internal_tags (
    id BIGINT PRIMARY KEY GENERATED BY DEFAULT AS IDENTITY,
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    is_system BOOLEAN NOT NULL,
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    modified_by BIGINT NOT NULL REFERENCES users,
    modified_time TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Reserve low-numbered tags for system use. The numbers aren't important, but it keeps the list of
-- values in R__TypeCodes.sql easier to maintain.
ALTER TABLE internal_tags ALTER COLUMN id RESTART WITH 10000;

CREATE TABLE organization_internal_tags (
    organization_id BIGINT NOT NULL REFERENCES organizations ON DELETE CASCADE,
    internal_tag_id BIGINT NOT NULL REFERENCES internal_tags ON DELETE CASCADE,
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (organization_id, internal_tag_id)
);
