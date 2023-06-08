CREATE TABLE user_preferences (
    user_id BIGINT NOT NULL REFERENCES users,
    organization_id BIGINT REFERENCES organizations,
    preferences JSONB NOT NULL
);

CREATE UNIQUE INDEX ON user_preferences (user_id) WHERE organization_id IS NULL;
CREATE UNIQUE INDEX ON user_preferences (user_id, organization_id) WHERE organization_id IS NOT NULL;
