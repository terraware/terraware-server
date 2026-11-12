package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.StratumId

/**
 * Existing strata whose maps or plot counts are changing, for the purpose of updating observations
 * with plots in them.
 *
 * Deleting a stratum clears the stratum ID in its history rows, so plots in strata that are being
 * deleted are also matched by the deleted strata's stable IDs. That lets observations be matched to
 * the same strata both before and after an edit is applied.
 */
data class AffectedStrata(
    val stratumIds: Set<StratumId>,
    /** Stable IDs of the strata in [stratumIds] that are being deleted. */
    val deletedStableIds: Set<StableId> = emptySet(),
) {
  fun isEmpty(): Boolean = stratumIds.isEmpty()
}
