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

class AccessionNotFoundException(val accessionId: AccessionId) :
    EntityNotFoundException("Accession $accessionId not found")

class AutomationNotFoundException(val automationId: AutomationId) :
    EntityNotFoundException("Automation $automationId not found")

class DeviceNotFoundException(val deviceId: DeviceId) :
    EntityNotFoundException("Device $deviceId not found")

class FacilityNotFoundException(val facilityId: FacilityId) :
    EntityNotFoundException("Facility $facilityId not found")

class FeatureNotFoundException(val featureId: FeatureId) :
    EntityNotFoundException("Feature $featureId not found")

/** A request to the Keycloak authentication server failed. */
open class KeycloakRequestFailedException(
    override val message: String,
    override val cause: Throwable? = null
) : IOException(message, cause)

/** Keycloak couldn't find a user that we expected to be able to find. */
class KeycloakUserNotFoundException(message: String) : EntityNotFoundException(message)

class LayerNotFoundException(val layerId: LayerId) :
    EntityNotFoundException("Layer $layerId not found")

class OrganizationNotFoundException(val organizationId: OrganizationId) :
    EntityNotFoundException("Organization $organizationId not found")

class PhotoNotFoundException(val photoId: PhotoId) :
    EntityNotFoundException("Photo $photoId not found")

class PlantNotFoundException(val featureId: FeatureId) :
    EntityNotFoundException("Plant with feature id $featureId not found")

class PlantObservationNotFoundException(val id: PlantObservationId) :
    EntityNotFoundException("Plant observation $id not found")

class ProjectNotFoundException(val projectId: ProjectId) :
    EntityNotFoundException("Project $projectId not found")

class SiteNotFoundException(val siteId: SiteId) : EntityNotFoundException("Site $siteId not found")

class SpeciesNotFoundException(val speciesId: SpeciesId) :
    EntityNotFoundException("Species $speciesId not found")

class SpeciesNameNotFoundException(val speciesNameId: SpeciesNameId) :
    EntityNotFoundException("Species name $speciesNameId not found")

class TimeseriesNotFoundException(
    val deviceId: DeviceId,
    val timeseriesName: String?,
    message: String = "Timeseries $timeseriesName not found on device $deviceId"
) : EntityNotFoundException(message) {
  constructor(deviceId: DeviceId) : this(deviceId, null, "Timeseries not found on device $deviceId")
}
