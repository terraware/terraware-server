package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import java.time.LocalDate

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

class InvalidObservationStartDateException(val startDate: LocalDate) :
    IllegalArgumentException(
        "Observation start date $startDate should be within a year from today")

class InvalidObservationEndDateException(val startDate: LocalDate, val endDate: LocalDate) :
    IllegalArgumentException(
        "Observation end date $endDate should be after and within two months of the start date $startDate")

class ObservationAlreadyStartedException(val observationId: ObservationId) :
    MismatchedStateException("Observation $observationId is already started")

class ObservationHasNoPlotsException(val observationId: ObservationId) :
    MismatchedStateException("No plots are eligible for observation $observationId")

class ObservationNotFoundException(val observationId: ObservationId) :
    EntityNotFoundException("Observation $observationId not found")

class PlantingNotFoundException(val plantingId: PlantingId) :
    EntityNotFoundException("Planting $plantingId not found")

class PlantingSiteNotFoundException(val plantingSiteId: PlantingSiteId) :
    EntityNotFoundException("Planting site $plantingSiteId not found")

class PlantingSubzoneNotFoundException(val plantingSubzoneId: PlantingSubzoneId) :
    EntityNotFoundException("Planting subzone $plantingSubzoneId not found")

class PlantingZoneNotFoundException(val plantingZoneId: PlantingZoneId) :
    EntityNotFoundException("Planting zone $plantingZoneId not found")

class PlantingSiteUploadProblemsException(val problems: List<String>) :
    Exception("Found problems in uploaded planting site file") {
  constructor(problem: String) : this(listOf(problem))
}

class PlotAlreadyClaimedException(val monitoringPlotId: MonitoringPlotId) :
    MismatchedStateException("Monitoring plot $monitoringPlotId is claimed by another user")

class PlotAlreadyCompletedException(val monitoringPlotId: MonitoringPlotId) :
    MismatchedStateException("Monitoring plot $monitoringPlotId observation is already completed")

class PlotNotClaimedException(val monitoringPlotId: MonitoringPlotId) :
    MismatchedStateException(
        "Monitoring plot $monitoringPlotId is not claimed by the current user")

class PlotNotFoundException(val monitoringPlotId: MonitoringPlotId) :
    EntityNotFoundException("Monitoring plot $monitoringPlotId not found")

class PlotNotInObservationException(
    val observationId: ObservationId,
    val monitoringPlotId: MonitoringPlotId
) :
    EntityNotFoundException(
        "Monitoring plot $monitoringPlotId is not assigned to observation $observationId")

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

class ObservationRescheduleStateException(val observationId: ObservationId) :
    MismatchedStateException("Observation $observationId cannot be rescheduled")

class ScheduleObservationWithoutPlantsException(val plantingSiteId: PlantingSiteId) :
    IllegalArgumentException(
        "Cannot schedule observation in planting site $plantingSiteId which has no reported plants in subzones")
