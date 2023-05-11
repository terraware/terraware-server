package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.PlantingSubzoneId

class PlantingSubzoneFullException(
    val plantingSubzoneId: PlantingSubzoneId,
    val plotsNeeded: Int,
    val plotsRemaining: Int
) :
    IllegalStateException(
        "Planting subzone $plantingSubzoneId needs $plotsNeeded temporary plots but only " +
            "$plotsRemaining available")
