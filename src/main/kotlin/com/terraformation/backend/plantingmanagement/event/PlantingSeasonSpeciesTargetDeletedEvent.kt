package com.terraformation.backend.plantingmanagement.event

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.SubstratumId

data class PlantingSeasonSpeciesTargetDeletedEvent(
    val plantingSeasonId: PlantingSeasonId,
    val speciesId: SpeciesId,
    val substratumId: SubstratumId,
)
