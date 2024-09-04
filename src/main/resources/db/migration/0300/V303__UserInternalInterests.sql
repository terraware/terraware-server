-- Create user_internal_interests table
CREATE TABLE accelerator.user_internal_interests (
    user_id BIGINT NOT NULL REFERENCES users,
    internal_interest_id INTEGER NOT NULL REFERENCES accelerator.internal_interests ON DELETE CASCADE,
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,

    PRIMARY KEY (user_id, internal_interest_id)
);

-- Move data from user_deliverable_categories  to user_internal_interests
INSERT INTO accelerator.user_internal_interests (user_id, internal_interest_id, created_by, created_time)
SELECT user_id, deliverable_category_id, created_by, created_time
FROM accelerator.user_deliverable_categories;

DROP TABLE accelerator.user_deliverable_categories;
