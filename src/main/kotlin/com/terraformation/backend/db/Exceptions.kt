package com.terraformation.backend.db

import com.terraformation.backend.db.default_schema.AutomationId
import com.terraformation.backend.db.default_schema.DeviceId
import com.terraformation.backend.db.default_schema.DeviceManagerId
import com.terraformation.backend.db.default_schema.FacilityId
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.FileId
import com.terraformation.backend.db.default_schema.InternalTagId
import com.terraformation.backend.db.default_schema.NotificationId
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ReportId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesProblemId
import com.terraformation.backend.db.default_schema.UploadId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.seedbank.AccessionId
import com.terraformation.backend.db.seedbank.StorageLocationId
import com.terraformation.backend.db.seedbank.ViabilityTestId
import com.terraformation.backend.db.seedbank.WithdrawalId
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

class AccessionNotFoundException(val accessionId: AccessionId) :
    EntityNotFoundException("Accession $accessionId not found")

class AutomationNotFoundException(val automationId: AutomationId) :
    EntityNotFoundException("Automation $automationId not found")

class CannotRemoveLastOwnerException(val organizationId: OrganizationId) :
    RuntimeException("Cannot remove last owner of organization $organizationId")

class DeviceManagerNotFoundException(val deviceManagerId: DeviceManagerId) :
    EntityNotFoundException("Device manager $deviceManagerId not found")

class DeviceNotFoundException(val deviceId: DeviceId) :
    EntityNotFoundException("Device $deviceId not found")

class FacilityAlreadyConnectedException(val facilityId: FacilityId) :
    DuplicateEntityException("Facility $facilityId already connected")

class FacilityNotFoundException(val facilityId: FacilityId) :
    EntityNotFoundException("Facility $facilityId not found")

class FacilityTypeMismatchException(val facilityId: FacilityId, val requiredType: FacilityType) :
    MismatchedStateException("Facility $facilityId is not of type ${requiredType.displayName}")

class FileNotFoundException(val fileId: FileId) : EntityNotFoundException("File $fileId not found")

class InternalTagIsSystemDefinedException(val internalTagId: InternalTagId) :
    AccessDeniedException("Tag $internalTagId is system-defined and may not be modified")

class InternalTagNotFoundException(val internalTagId: InternalTagId) :
    EntityNotFoundException("Tag $internalTagId not found")

/** A request to the Keycloak authentication server failed. */
open class KeycloakRequestFailedException(
    override val message: String,
    override val cause: Throwable? = null
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

class ReportAlreadySubmittedException(val reportId: ReportId) :
    MismatchedStateException("Report $reportId has already been submitted and cannot be modified")

class ReportLockedException(val reportId: ReportId) :
    MismatchedStateException("Report $reportId is locked by another user")

class ReportSubmittedException(val reportId: ReportId) :
    MismatchedStateException("Report $reportId has been submitted")

class ReportNotFoundException(val reportId: ReportId) :
    EntityNotFoundException("Report $reportId not found")

class ReportNotLockedException(val reportId: ReportId) :
    MismatchedStateException("Report $reportId is not locked")

class ScientificNameExistsException(val name: String?) :
    DuplicateEntityException("Scientific name $name already exists")

class ScientificNameNotFoundException(val name: String) :
    EntityNotFoundException("Scientific name $name not found")

class SpeciesNotFoundException(val speciesId: SpeciesId) :
    EntityNotFoundException("Species $speciesId not found")

class SpeciesProblemNotFoundException(val speciesProblemId: SpeciesProblemId) :
    EntityNotFoundException("Species problem $speciesProblemId not found")

class SpeciesProblemHasNoSuggestionException(val speciesProblemId: SpeciesProblemId) :
    MismatchedStateException("Species problem $speciesProblemId has no suggested value")

class StorageLocationNameExistsException(val name: String) :
    DuplicateEntityException("Storage location $name already exists at facility")

class StorageLocationInUseException(val storageLocationId: StorageLocationId) :
    MismatchedStateException("Storage location $storageLocationId is in use")

class StorageLocationNotFoundException(val storageLocationId: StorageLocationId) :
    EntityNotFoundException("Storage location $storageLocationId not found")

class TimeseriesNotFoundException(
    val deviceId: DeviceId,
    val timeseriesName: String?,
    message: String = "Timeseries $timeseriesName not found on device $deviceId"
) : EntityNotFoundException(message) {
  constructor(deviceId: DeviceId) : this(deviceId, null, "Timeseries not found on device $deviceId")
}

class UploadNotAwaitingActionException(val uploadId: UploadId) :
    MismatchedStateException("Upload $uploadId is not awaiting user action")

class UploadNotFoundException(val uploadId: UploadId) :
    EntityNotFoundException("Upload $uploadId not found")

class UserAlreadyInOrganizationException(val userId: UserId, val organizationId: OrganizationId) :
    DuplicateEntityException("User is already in organization")

class UserNotFoundException(val userId: UserId) : EntityNotFoundException("User $userId not found")

class ViabilityTestNotFoundException(val viabilityTestId: ViabilityTestId) :
    EntityNotFoundException("Viability test $viabilityTestId not found")

class WithdrawalNotFoundException(val withdrawalId: WithdrawalId) :
    EntityNotFoundException("Withdrawal $withdrawalId not found")

class NotificationNotFoundException(val notificationId: NotificationId) :
    EntityNotFoundException("Notification $notificationId not found")
