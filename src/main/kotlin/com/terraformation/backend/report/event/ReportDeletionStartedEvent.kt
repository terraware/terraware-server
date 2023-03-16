package com.terraformation.backend.report.event

import com.terraformation.backend.db.default_schema.ReportId

/**
 * Published when we start deleting all the data related to a report, but before the report has
 * actually been deleted from the database.
 */
data class ReportDeletionStartedEvent(val reportId: ReportId)
