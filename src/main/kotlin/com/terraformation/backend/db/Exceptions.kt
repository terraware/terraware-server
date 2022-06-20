package com.terraformation.backend.db

import java.io.IOException

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

class PhotoNotFoundException(val photoId: PhotoId) :
    EntityNotFoundException("Photo $photoId not found")

class ProjectNotFoundException(val projectId: ProjectId) :
    EntityNotFoundException("Project $projectId not found")

class ProjectOrganizationWideException(val projectId: ProjectId) :
    Exception("Project $projectId is organization-wide")

class SiteNotFoundException(val siteId: SiteId) : EntityNotFoundException("Site $siteId not found")

class SpeciesNotFoundException(val speciesId: SpeciesId) :
    EntityNotFoundException("Species $speciesId not found")

class SpeciesProblemNotFoundException(val speciesProblemId: SpeciesProblemId) :
    EntityNotFoundException("Species problem $speciesProblemId not found")

class SpeciesProblemHasNoSuggestionException(val speciesProblemId: SpeciesProblemId) :
    MismatchedStateException("Species problem $speciesProblemId has no suggested value")

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

class UserAlreadyInProjectException(val userId: UserId, val projectId: ProjectId) :
    DuplicateEntityException("User is already in project")

class UserNotFoundException(val userId: UserId) : EntityNotFoundException("User $userId not found")

class NotificationNotFoundException(val notificationId: NotificationId) :
    EntityNotFoundException("Notification $notificationId not found")
