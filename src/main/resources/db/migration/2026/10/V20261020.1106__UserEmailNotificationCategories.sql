CREATE TABLE user_email_notification_categories
(
    user_id                        BIGINT  NOT NULL REFERENCES users ON DELETE CASCADE,
    email_notification_category_id INTEGER NOT NULL REFERENCES email_notification_categories,

    PRIMARY KEY (user_id, email_notification_category_id)
);

CREATE INDEX ON user_email_notification_categories (email_notification_category_id);

-- This is also in R__TypeCodes.sql, but is repeated here to ensure migration is successful
INSERT INTO email_notification_categories (id, name)
VALUES (1, 'SeedBank'),
       (2, 'Nursery'),
       (3, 'PlantingAndObservations'),
       (4, 'Accelerator')
ON CONFLICT (id) DO UPDATE SET name = excluded.name;

-- Users who had opted into email notifications are opted into every category that applies to
-- them. Individual users get the accelerator category (4) only if they're members of
-- organizations with accelerator or application projects. Funders (user type 5) only get the
-- accelerator category.
INSERT INTO user_email_notification_categories (user_id, email_notification_category_id)
SELECT users.id, categories.id
FROM users
CROSS JOIN email_notification_categories categories
WHERE users.email_notifications_enabled
AND (
    (users.user_type_id = 1 AND categories.id <> 4)
    OR (users.user_type_id = 5 AND categories.id = 4)
    OR (
        users.user_type_id = 1
        AND categories.id = 4
        AND EXISTS (
            SELECT 1
            FROM organization_users
            JOIN projects ON organization_users.organization_id = projects.organization_id
            LEFT JOIN accelerator.applications ON projects.id = applications.project_id
            WHERE organization_users.user_id = users.id
            AND (projects.phase_id IS NOT NULL OR applications.id IS NOT NULL)
        )
    )
);
