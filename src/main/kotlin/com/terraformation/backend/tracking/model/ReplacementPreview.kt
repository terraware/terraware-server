package com.terraformation.backend.tracking.model

/** What would happen if a plot in an observation were replaced. */
data class ReplacementPreview(
    /** Other upcoming observations that would be affected by the replacement. */
    val affectedObservations: List<AffectedUpcomingObservation>,
    /**
     * True if the observation would be deleted if no replacement plot could be found, because the
     * plot is the only plot in an observation that has started. Upcoming observations aren't
     * deleted; they're given new plots when they start.
     */
    val mayDeleteObservation: Boolean,
)
