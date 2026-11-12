package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.ObservationId

/**
 * Active observations that have incomplete plots in strata whose map or plot counts are changing,
 * classified by what should happen to them.
 */
data class AffectedObservations(
    /**
     * In-progress or overdue observations that have at least one completed plot (in any stratum).
     * These are abandoned, since their completed data can't be kept if their plots change.
     */
    val toAbandon: List<ObservationId>,
    /**
     * Upcoming observations, and in-progress or overdue observations with no completed plots. These
     * get new plots in the affected strata.
     */
    val toReassign: List<ObservationId>,
)
