package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId

class PlantingSeasonNotFoundException(val plantingSeasonId: PlantingSeasonId) :
    EntityNotFoundException("Planting season $plantingSeasonId not found")

class PlantingSeasonExistsException(plantingSiteId: PlantingSiteId, name: String) :
    MismatchedStateException(
        "Planting Site $plantingSiteId already has a planting season with name $name"
    )
