package com.terraformation.backend.customer.model

import com.terraformation.backend.db.default_schema.EmailNotificationCategory

/**
 * A user's opt-in status for each email notification category that applies to them. Categories that
 * don't apply to the user aren't included.
 */
data class EmailNotificationSettingsModel(
    val categories: Map<EmailNotificationCategory, Boolean>,
)
