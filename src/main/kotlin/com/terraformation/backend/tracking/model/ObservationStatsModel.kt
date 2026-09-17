package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import java.time.Instant

data class ObservationSubstratumStatsModel(
    val completedTime: Instant?,
    val observationId: ObservationId?,
    val plantingDensity: Int?,
    val substratumId: SubstratumId,
    val survivalRate: Int?,
    val totalPlants: Int?,
    val totalSpecies: Int?,
)

data class ObservationStratumStatsModel(
    val completedTime: Instant?,
    val observationId: ObservationId?,
    val plantingDensity: Int?,
    val stratumId: StratumId,
    val substrata: List<ObservationSubstratumStatsModel>,
    val survivalRate: Int?,
    val totalPlants: Int?,
    val totalSpecies: Int?,
)

data class ObservationSiteStatsModel(
    val completedTime: Instant?,
    val observationId: ObservationId?,
    val plantingDensity: Int?,
    val plantingSiteId: PlantingSiteId,
    val strata: List<ObservationStratumStatsModel>,
    val survivalRate: Int?,
    val totalPlants: Int?,
    val totalSpecies: Int?,
)
