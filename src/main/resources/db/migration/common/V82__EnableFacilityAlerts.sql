UPDATE users
SET email_notifications_enabled = TRUE
WHERE email IN (
    SELECT email
    FROM facility_alert_recipients
);

DROP TABLE facility_alert_recipients;
