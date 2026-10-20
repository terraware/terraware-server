CREATE TABLE user_email_notification_categories
(
    user_id                        BIGINT  NOT NULL REFERENCES users ON DELETE CASCADE,
    email_notification_category_id INTEGER NOT NULL REFERENCES email_notification_categories,

    PRIMARY KEY (user_id, email_notification_category_id)
);

CREATE INDEX ON user_email_notification_categories (email_notification_category_id);

-- This is also in R__TypeCodes.sql, but is repeated here to ensure migration is successful
INSERT INTO email_notification_categories (id, name)
VALUES (1, 'Seed Bank'),
       (2, 'Nursery'),
       (3, 'Planting and Observations'),
       (4, 'Accelerator')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

-- Users who had opted into email notifications are opted into every category.
INSERT INTO user_email_notification_categories (user_id, email_notification_category_id)
SELECT users.id, categories.id
FROM users
CROSS JOIN email_notification_categories categories
WHERE users.email_notifications_enabled;
