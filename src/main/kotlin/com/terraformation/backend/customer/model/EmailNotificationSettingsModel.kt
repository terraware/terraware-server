package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.EmailNotificationCategory

/**
 * A user's email notification settings. Categories that don't apply to the user aren't included in
 * [categories].
 */
data class EmailNotificationSettingsModel(
    val categories: Map<EmailNotificationCategory, Boolean>,
    /**
     * Master switch for email notifications. If false, the user doesn't receive email notifications
     * that respect opt-in settings, regardless of which categories they've opted into.
     */
    val emailNotificationsEnabled: Boolean,
)
