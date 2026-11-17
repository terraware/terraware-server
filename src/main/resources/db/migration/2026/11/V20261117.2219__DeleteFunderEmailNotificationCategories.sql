DELETE FROM user_email_notification_categories
USING users
WHERE user_email_notification_categories.user_id = users.id
AND users.user_type_id = 5; -- Funder
