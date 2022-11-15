package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.tracking.PlantingSiteId

class CrossOrganizationDeliveryNotAllowedException(
    val facilityId: FacilityId,
    val plantingSiteId: PlantingSiteId
) :
    MismatchedStateException(
        "Cannot transfer from nursery $facilityId to planting site $plantingSiteId because they " +
            "are in different organizations")

class DeliveryMissingPlotException(val plantingSiteId: PlantingSiteId) :
    MismatchedStateException("Deliveries to planting site $plantingSiteId must include plot IDs")

class PlantingSiteNotFoundException(val plantingSiteId: PlantingSiteId) :
    EntityNotFoundException("Planting site $plantingSiteId not found")

class PlantingSiteUploadProblemsException(val problems: List<String>) :
    Exception("Found problems in uploaded planting site file") {
  constructor(problem: String) : this(listOf(problem))
}
