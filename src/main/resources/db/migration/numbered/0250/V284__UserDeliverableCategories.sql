CREATE TABLE accelerator.user_deliverable_categories (
    user_id BIGINT NOT NULL REFERENCES users,
    deliverable_category_id INTEGER NOT NULL REFERENCES accelerator.deliverable_categories ON DELETE CASCADE,
    created_by BIGINT NOT NULL REFERENCES users,
    created_time TIMESTAMP WITH TIME ZONE NOT NULL,

    PRIMARY KEY (user_id, deliverable_category_id)
);

CREATE INDEX ON accelerator.user_deliverable_categories (created_by);
