package com.terraformation.backend.tracking.db

import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.MismatchedStateException
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.nursery.WithdrawalId
import com.terraformation.backend.db.tracking.DeliveryId
import com.terraformation.backend.db.tracking.DraftPlantingSiteId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteHistoryId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.RecordedTreeId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.tracking.model.PlantingSiteValidationFailure
import java.time.LocalDate

class BiomassSpeciesNotFoundException(val speciesId: SpeciesId?, val speciesName: String?) :
    EntityNotFoundException("Species ${speciesId ?: speciesName} not found in observation")

class CrossDeliveryReassignmentNotAllowedException(
    val plantingId: PlantingId,
    val plantingDeliveryId: DeliveryId,
    val destinationDeliveryId: DeliveryId,
) :
    MismatchedStateException(
        "Planting $plantingId is in delivery $plantingDeliveryId; cannot reassign to " +
            "delivery $destinationDeliveryId"
    )

class CrossOrganizationDeliveryNotAllowedException(
    val facilityId: FacilityId,
    val plantingSiteId: PlantingSiteId,
) :
    MismatchedStateException(
        "Cannot transfer from nursery $facilityId to planting site $plantingSiteId because they " +
            "are in different organizations"
    )

class DeliveryMissingSubzoneException(val plantingSiteId: PlantingSiteId) :
    MismatchedStateException("Deliveries to planting site $plantingSiteId must include subzone IDs")

class DeliveryNotFoundException(val deliveryId: DeliveryId) :
    EntityNotFoundException("Delivery $deliveryId not found")

class DraftPlantingSiteNotFoundException(val draftSiteId: DraftPlantingSiteId) :
    EntityNotFoundException("Draft planting site $draftSiteId not found")

class InvalidObservationStartDateException(val startDate: LocalDate) :
    IllegalArgumentException("Observation start date $startDate should be within a year from today")

class InvalidObservationEndDateException(val startDate: LocalDate, val endDate: LocalDate) :
    IllegalArgumentException(
        "Observation end date $endDate should be after and within two months of the start date $startDate"
    )

class ObservationAlreadyEndedException(val observationId: ObservationId) :
    MismatchedStateException("Observation $observationId has already ended")

class ObservationAlreadyStartedException(val observationId: ObservationId) :
    MismatchedStateException("Observation $observationId is already started")

class ObservationHasNoSubzonesException(val observationId: ObservationId) :
    MismatchedStateException("No subzones were requested for observation $observationId")

class ObservationNotFoundException(val observationId: ObservationId) :
    EntityNotFoundException("Observation $observationId not found")

class ObservationPlotNotFoundException(
    val observationId: ObservationId,
    val plotId: MonitoringPlotId,
) : EntityNotFoundException("Observation $observationId plot $plotId not found")

class PlantingNotFoundException(val plantingId: PlantingId) :
    EntityNotFoundException("Planting $plantingId not found")

class PlantingSeasonNotFoundException(val plantingSeasonId: PlantingSeasonId) :
    EntityNotFoundException("Planting season $plantingSeasonId not found")

class PlantingSiteHistoryNotFoundException(val plantingSiteHistoryId: PlantingSiteHistoryId) :
    EntityNotFoundException("Planting site history $plantingSiteHistoryId not found")

class PlantingSiteNotDetailedException(val plantingSiteId: PlantingSiteId) :
    MismatchedStateException("Planting site $plantingSiteId is not a detailed planting site")

class PlantingSiteNotFoundException(val plantingSiteId: PlantingSiteId) :
    EntityNotFoundException("Planting site $plantingSiteId not found")

class PlantingSubzoneNotFoundException(val plantingSubzoneId: SubstratumId) :
    EntityNotFoundException("Planting subzone $plantingSubzoneId not found")

class PlantingZoneNotFoundException(val plantingZoneId: StratumId) :
    EntityNotFoundException("Planting zone $plantingZoneId not found")

class PlantingSiteMapInvalidException(val problems: List<PlantingSiteValidationFailure>) :
    RuntimeException("Found problems in planting site edit")

class PlotAlreadyClaimedException(val monitoringPlotId: MonitoringPlotId) :
    MismatchedStateException("Monitoring plot $monitoringPlotId is claimed by another user")

class PlotAlreadyCompletedException(val monitoringPlotId: MonitoringPlotId) :
    MismatchedStateException("Monitoring plot $monitoringPlotId observation is already completed")

class PlotNotClaimedException(val monitoringPlotId: MonitoringPlotId) :
    MismatchedStateException("Monitoring plot $monitoringPlotId is not claimed by the current user")

class PlotNotCompletedException(val monitoringPlotId: MonitoringPlotId) :
    MismatchedStateException("Monitoring plot $monitoringPlotId observation is not completed")

class PlotNotFoundException(val monitoringPlotId: MonitoringPlotId) :
    EntityNotFoundException("Monitoring plot $monitoringPlotId not found")

class PlotNotInObservationException(
    val observationId: ObservationId,
    val monitoringPlotId: MonitoringPlotId,
) :
    EntityNotFoundException(
        "Monitoring plot $monitoringPlotId is not assigned to observation $observationId"
    )

class PlotSizeNotReplaceableException(
    val observationId: ObservationId,
    val monitoringPlotId: MonitoringPlotId,
) :
    MismatchedStateException(
        "Monitoring plot $monitoringPlotId has old size; can't replace it in observation $observationId"
    )

class ReassignmentExistsException(val plantingId: PlantingId) :
    MismatchedStateException(
        "Cannot reassign from planting $plantingId because it already has a reassignment"
    )

class ReassignmentOfReassignmentNotAllowedException(val plantingId: PlantingId) :
    MismatchedStateException(
        "Cannot reassign from planting $plantingId because it is a reassignment"
    )

class ReassignmentOfUndoneWithdrawalNotAllowedException(val deliveryId: DeliveryId) :
    MismatchedStateException(
        "Cannot reassign delivery $deliveryId because its withdrawal has been undone"
    )

class ReassignmentOfUndoNotAllowedException(val deliveryId: DeliveryId) :
    MismatchedStateException(
        "Cannot reassign delivery $deliveryId because it is an undo of another delivery"
    )

class ReassignmentTooLargeException(val plantingId: PlantingId) :
    MismatchedStateException(
        "Cannot reassign more plants from planting $plantingId than were originally delivered"
    )

class ReassignmentToSamePlotNotAllowedException(val plantingId: PlantingId) :
    IllegalArgumentException("Cannot reassign from planting $plantingId to its original plot")

class RecordedTreeNotFoundException(val recordedTreeId: RecordedTreeId) :
    EntityNotFoundException("Recorded tree $recordedTreeId not found")

class ObservationRescheduleStateException(val observationId: ObservationId) :
    MismatchedStateException("Observation $observationId cannot be rescheduled")

class ScheduleObservationWithoutPlantsException(val plantingSiteId: PlantingSiteId) :
    IllegalArgumentException(
        "Cannot schedule observation in planting site $plantingSiteId which has no reported plants in subzones"
    )

class ShapefilesInvalidException(val problems: List<String>) :
    RuntimeException("Found problems in planting site map data") {
  constructor(problem: String) : this(listOf(problem))
}

class SpeciesInWrongOrganizationException(val speciesId: SpeciesId) :
    MismatchedStateException("Species $speciesId is in the wrong organization")

class WithdrawalNotUndoException(val withdrawalId: WithdrawalId) :
    MismatchedStateException("Withdrawal $withdrawalId is not an undo withdrawal")
