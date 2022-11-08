package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.tracking.PlantingSiteId

class PlantingSiteNotFoundException(val plantingSiteId: PlantingSiteId) :
    EntityNotFoundException("Planting site $plantingSiteId not found")

class PlantingSiteUploadProblemsException(val problems: List<String>) :
    Exception("Found problems in uploaded planting site file") {
  constructor(problem: String) : this(listOf(problem))
}
