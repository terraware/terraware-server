package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId

class CrossDeliveryReassignmentNotAllowedException(
    val plantingId: PlantingId,
    val plantingDeliveryId: DeliveryId,
    val destinationDeliveryId: DeliveryId,
) :
    MismatchedStateException(
        "Planting $plantingId is in delivery $plantingDeliveryId; cannot reassign to " +
            "delivery $destinationDeliveryId")

class CrossOrganizationDeliveryNotAllowedException(
    val facilityId: FacilityId,
    val plantingSiteId: PlantingSiteId
) :
    MismatchedStateException(
        "Cannot transfer from nursery $facilityId to planting site $plantingSiteId because they " +
            "are in different organizations")

class DeliveryMissingSubzoneException(val plantingSiteId: PlantingSiteId) :
    MismatchedStateException(
        "Deliveries to planting site $plantingSiteId must include subzone IDs")

class DeliveryNotFoundException(val deliveryId: DeliveryId) :
    EntityNotFoundException("Delivery $deliveryId not found")

class PlantingNotFoundException(val plantingId: PlantingId) :
    EntityNotFoundException("Planting $plantingId not found")

class PlantingSiteNotFoundException(val plantingSiteId: PlantingSiteId) :
    EntityNotFoundException("Planting site $plantingSiteId not found")

class PlantingSiteUploadProblemsException(val problems: List<String>) :
    Exception("Found problems in uploaded planting site file") {
  constructor(problem: String) : this(listOf(problem))
}

class ReassignmentExistsException(val plantingId: PlantingId) :
    MismatchedStateException(
        "Cannot reassign from planting $plantingId because it already has a reassignment")

class ReassignmentOfReassignmentNotAllowedException(val plantingId: PlantingId) :
    MismatchedStateException(
        "Cannot reassign from planting $plantingId because it is a reassignment")

class ReassignmentTooLargeException(val plantingId: PlantingId) :
    MismatchedStateException(
        "Cannot reassign more plants from planting $plantingId than were originally delivered")

class ReassignmentToSamePlotNotAllowedException(val plantingId: PlantingId) :
    IllegalArgumentException("Cannot reassign from planting $plantingId to its original plot")
