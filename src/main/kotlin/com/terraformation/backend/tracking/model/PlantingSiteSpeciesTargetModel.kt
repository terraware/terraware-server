package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.StratumId

/**
 * A species that is targeted for planting at a planting site.
 *
 * @param stratumIds The strata of the planting site where the species is targeted for planting.
 *   There are no per-stratum target plant counts.
 * @param targetPlants The number of plants targeted for the site as a whole, or null if there is no
 *   target.
 */
data class PlantingSiteSpeciesTargetModel(
    val speciesId: SpeciesId,
    val stratumIds: Set<StratumId> = emptySet(),
    val targetPlants: Long? = null,
)
