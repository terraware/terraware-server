package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.ObservationType
import java.time.LocalDate

/** What would happen to an upcoming observation if a plot it includes were replaced long-term. */
enum class AffectedUpcomingObservationOutcome {
  /** The observation would be given new plots in the replaced plot's stratum. */
  PlotsReassigned,

  /** The observation couldn't be given new plots, so it would be deleted. */
  ObservationDeleted,
}

/**
 * An upcoming observation that would be affected by a long-term replacement of a plot in a
 * different observation.
 */
data class AffectedUpcomingObservation(
    val observationId: ObservationId,
    val observationType: ObservationType,
    val startDate: LocalDate,
    val outcome: AffectedUpcomingObservationOutcome,
)
