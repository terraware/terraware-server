package com.terraformation.backend.db

import com.terraformation.backend.db.accelerator.EventId
import com.terraformation.backend.db.accelerator.ProjectMetricId
import com.terraformation.backend.db.accelerator.ProjectReportConfigId
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.StandardMetricId
import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.DisclaimerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SeedFundReportId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesProblemId
import com.terraformation.backend.db.default_schema.SubLocationId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.WithdrawalId
import com.terraformation.backend.db.tracking.PlantingSiteId
import java.io.IOException
import org.springframework.security.access.AccessDeniedException

/**
 * Thrown when an entity wasn't found when it should have been.
 *
 * Subclasses of this are mapped to HTTP 404 Not Found if they are thrown by a controller method.
 */
abstract class EntityNotFoundException(message: String) : RuntimeException(message) {
  override val message: String
    get() = super.message!!
}

/**
 * Thrown when the system detects that the client has a stale copy of some piece of data but is
 * attempting to update the data.
 *
 * Subclasses of this are mapped to HTTP 412 Precondition Failed if they are thrown by a controller
 * method.
 */
abstract class EntityStaleException(message: String) : RuntimeException(message) {
  override val message: String
    get() = super.message!!
}

/**
 * Thrown when the system detects a duplicate piece of data that needs to be unique.
 *
 * Subclasses of this are mapped to HTTP 409 Conflict if they are thrown by a controller method.
 */
abstract class DuplicateEntityException(message: String) : RuntimeException(message) {
  override val message: String
    get() = super.message!!
}

/**
 * Thrown when the system detects that something is in the wrong state to perform an operation that
 * would otherwise be allowed.
 *
 * Subclasses of this are mapped to HTTP 409 Conflict if they are thrown by a controller method.
 */
abstract class MismatchedStateException(message: String) : RuntimeException(message) {
  override val message: String
    get() = super.message!!
}

class AcceleratorProjectNotFoundException(val projectId: ProjectId) :
    EntityNotFoundException("Project $projectId not found or not in accelerator")

class AccessionNotFoundException(val accessionId: AccessionId) :
    EntityNotFoundException("Accession $accessionId not found")

class AccessionSpeciesHasDeliveriesException(val accessionId: AccessionId) :
    MismatchedStateException(
        "Accession $accessionId has deliveries so its species cannot be changed"
    )

class AutomationNotFoundException(val automationId: AutomationId) :
    EntityNotFoundException("Automation $automationId not found")

class CannotRemoveLastOwnerException(val organizationId: OrganizationId) :
    RuntimeException("Cannot remove last owner of organization $organizationId")

class CountryNotFoundException(val countryCode: String) :
    EntityNotFoundException("Country $countryCode not found")

class DeviceManagerNotFoundException(val deviceManagerId: DeviceManagerId) :
    EntityNotFoundException("Device manager $deviceManagerId not found")

class DeviceNotFoundException(val deviceId: DeviceId) :
    EntityNotFoundException("Device $deviceId not found")

class DisclaimerNotFoundException(val disclaimerId: DisclaimerId) :
    EntityNotFoundException("Disclaimer $disclaimerId not found")

class EventNotFoundException(val eventId: EventId) :
    EntityNotFoundException("Event $eventId not found")

class FacilityAlreadyConnectedException(val facilityId: FacilityId) :
    DuplicateEntityException("Facility $facilityId already connected")

class FacilityNotFoundException(val facilityId: FacilityId) :
    EntityNotFoundException("Facility $facilityId not found")

class FacilityTypeMismatchException(val facilityId: FacilityId, val requiredType: FacilityType) :
    MismatchedStateException("Facility $facilityId is not of type ${requiredType.jsonValue}")

class FileNotFoundException(val fileId: FileId) : EntityNotFoundException("File $fileId not found")

class InternalTagIsSystemDefinedException(val internalTagId: InternalTagId) :
    AccessDeniedException("Tag $internalTagId is system-defined and may not be modified")

class InternalTagNotFoundException(val internalTagId: InternalTagId) :
    EntityNotFoundException("Tag $internalTagId not found")

class InvalidTerraformationContactEmail(val email: String) :
    RuntimeException("Invalid Terraformation Contact email $email")

/** A request to the Keycloak authentication server failed. */
open class KeycloakRequestFailedException(
    override val message: String,
    override val cause: Throwable? = null,
) : IOException(message, cause)

/** Keycloak couldn't find a user that we expected to be able to find. */
class KeycloakUserNotFoundException(message: String) : EntityNotFoundException(message)

/**
 * Thrown when the system detects that an operation is already in progress that prevents a requested
 * operation from proceeding. This is mapped to HTTP 423 Locked if it is thrown by a controller
 * method.
 */
class OperationInProgressException(message: String) : RuntimeException(message) {
  override val message: String
    get() = super.message!!
}

class OrganizationHasOtherUsersException(val organizationId: OrganizationId) :
    RuntimeException("Organization $organizationId has other users")

class OrganizationNotFoundException(val organizationId: OrganizationId) :
    EntityNotFoundException("Organization $organizationId not found")

class PlantingSiteInUseException(val plantingSiteId: PlantingSiteId) :
    MismatchedStateException("Planting site $plantingSiteId is in use")

class ProjectMetricNotFoundException(val metricId: ProjectMetricId) :
    EntityNotFoundException("Project Metric $metricId not found")

class ProjectNameInUseException(val name: String) :
    DuplicateEntityException("Project name $name already in use")

class ProjectInDifferentOrganizationException :
    MismatchedStateException("Project is in a different organization")

class ProjectNotFoundException(val projectId: ProjectId) :
    EntityNotFoundException("Project $projectId not found")

class ReportConfigNotFoundException(val configId: ProjectReportConfigId) :
    EntityNotFoundException("Report config $configId not found")

class ReportNotFoundException(val reportId: ReportId) :
    EntityNotFoundException("Report $reportId not found")

class ScientificNameExistsException(val name: String?) :
    DuplicateEntityException("Scientific name $name already exists")

class SeedFundReportAlreadySubmittedException(val reportId: SeedFundReportId) :
    MismatchedStateException(
        "Seed Fund Report $reportId has already been submitted and cannot be modified"
    )

class SeedFundReportLockedException(val reportId: SeedFundReportId) :
    MismatchedStateException("Seed Fund Report $reportId is locked by another user")

class SeedFundReportSubmittedException(val reportId: SeedFundReportId) :
    MismatchedStateException("Seed Fund Report $reportId has been submitted")

class SeedFundReportNotFoundException(val reportId: SeedFundReportId) :
    EntityNotFoundException("Seed Fund Report $reportId not found")

class SeedFundReportNotLockedException(val reportId: SeedFundReportId) :
    MismatchedStateException("Seed Fund Report $reportId is not locked")

class SpeciesInUseException(val speciesId: SpeciesId) :
    MismatchedStateException("Species $speciesId is in use")

class SpeciesNotFoundException(val speciesId: SpeciesId) :
    EntityNotFoundException("Species $speciesId not found")

class SpeciesProblemNotFoundException(val speciesProblemId: SpeciesProblemId) :
    EntityNotFoundException("Species problem $speciesProblemId not found")

class SpeciesProblemHasNoSuggestionException(val speciesProblemId: SpeciesProblemId) :
    MismatchedStateException("Species problem $speciesProblemId has no suggested value")

class StandardMetricNotFoundException(val metricId: StandardMetricId) :
    EntityNotFoundException("Standard Metric $metricId not found")

class SubLocationNameExistsException(val name: String) :
    DuplicateEntityException("Sub-location $name already exists at facility")

class SubLocationInUseException(val subLocationId: SubLocationId) :
    MismatchedStateException("Sub-location $subLocationId is in use")

class SubLocationAtWrongFacilityException(val subLocationId: SubLocationId) :
    MismatchedStateException("Sub-location $subLocationId is at a different facility")

class SubLocationNotFoundException(val subLocationId: SubLocationId) :
    EntityNotFoundException("Sub-location $subLocationId not found")

class TimeseriesNotFoundException(
    val deviceId: DeviceId,
    val timeseriesName: String?,
    message: String = "Timeseries $timeseriesName not found on device $deviceId",
) : EntityNotFoundException(message) {
  constructor(deviceId: DeviceId) : this(deviceId, null, "Timeseries not found on device $deviceId")
}

class TokenNotFoundException(val token: String) :
    EntityNotFoundException("Token $token not found or expired")

class UploadNotAwaitingActionException(val uploadId: UploadId) :
    MismatchedStateException("Upload $uploadId is not awaiting user action")

class UploadNotFoundException(val uploadId: UploadId) :
    EntityNotFoundException("Upload $uploadId not found")

class UserAlreadyInOrganizationException(val userId: UserId, val organizationId: OrganizationId) :
    DuplicateEntityException("User is already in organization")

class UserNotFoundException(val userId: UserId) : EntityNotFoundException("User $userId not found")

class UserNotFoundForEmailException(val email: String) :
    EntityNotFoundException("User with email $email not found")

class ViabilityTestNotFoundException(val viabilityTestId: ViabilityTestId) :
    EntityNotFoundException("Viability test $viabilityTestId not found")

class WithdrawalNotFoundException(val withdrawalId: WithdrawalId) :
    EntityNotFoundException("Withdrawal $withdrawalId not found")

class NotificationNotFoundException(val notificationId: NotificationId) :
    EntityNotFoundException("Notification $notificationId not found")
