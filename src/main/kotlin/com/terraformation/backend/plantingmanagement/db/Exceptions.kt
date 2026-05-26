package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import java.time.LocalDate

class PlantingSeasonNotFoundException(val plantingSeasonId: PlantingSeasonId) :
    EntityNotFoundException("Planting season $plantingSeasonId not found")

class PlantingSeasonExistsException(plantingSiteId: PlantingSiteId, name: String) :
    MismatchedStateException(
        "Planting Site $plantingSiteId already has a planting season with name $name"
    )

class PlantingSeasonScheduledDateExistsException(
    plantingSeasonId: PlantingSeasonId,
    date: LocalDate,
) :
    MismatchedStateException(
        "Planting Season $plantingSeasonId already has a scheduled planting date for $date"
    )
