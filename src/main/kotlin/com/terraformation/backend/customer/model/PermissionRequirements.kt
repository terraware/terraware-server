package com.terraformation.backend.customer.model

import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.DeviceNotFoundException
import com.terraformation.backend.db.EntityNotFoundException
import com.terraformation.backend.db.FacilityId
import com.terraformation.backend.db.FacilityNotFoundException
import com.terraformation.backend.db.FeatureId
import com.terraformation.backend.db.FeatureNotFoundException
import com.terraformation.backend.db.LayerId
import com.terraformation.backend.db.LayerNotFoundException
import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.OrganizationNotFoundException
import com.terraformation.backend.db.PhotoId
import com.terraformation.backend.db.PhotoNotFoundException
import com.terraformation.backend.db.ProjectId
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.SiteId
import com.terraformation.backend.db.SiteNotFoundException
import com.terraformation.backend.db.SpeciesId
import org.springframework.security.access.AccessDeniedException

/**
 * Declarative permission checks. The methods here check whether the user has permission to perform
 * various actions. If the user doesn't have permission, they throw exceptions. A method may throw
 * more than one kind of exception.
 *
 * You will usually not reference this class directly, but rather call its methods from a lambda
 * function you pass to [requirePermissions]. For example:
 * ```
 * requirePermissions { createDevice(facilityId) }
 * ```
 *
 * This class should not do any data access of its own; it should call the various `canX` methods on
 * [UserModel] to determine whether the user has permission to do something.
 *
 * ## Exception behavior
 *
 * To ensure consistent behavior of the permission checks, the methods here should throw exceptions
 * using the following set of rules.
 *
 * - Always throw the most specific exception class that describes the failure. For example, the
 * rules will say to throw [EntityNotFoundException] but you'd actually want to throw, e.g.,
 * [LayerNotFoundException] to give the caller more information about what failed.
 *
 * - Exception messages may be returned to the client and should never include any information that
 * you wouldn't want end users to see.
 *
 * - Exception messages should, if possible, include the identifier of the object the user didn't
 * have permission to operate on.
 *
 * - For read actions, if the object doesn't exist, throw [EntityNotFoundException]. Note that the
 * permission checking methods in [UserModel] are required to return false if the target object
 * doesn't exist, so in most cases, it should suffice to just check for read permission.
 *
 * - For read actions, if the user doesn't have permission to see the object at all, throw
 * [EntityNotFoundException]. We want inaccessible data to act as if it doesn't exist at all.
 *
 * - For fine-grained read actions, if the user has permission to see the object as a whole but
 * doesn't have permission to see the specific part of the object, throw [AccessDeniedException].
 *
 * - For creation actions, if the user doesn't have permission to see the parent object (if any),
 * throw [EntityNotFoundException].
 *
 * - For write actions (including delete), if the user has permission to read the object but doesn't
 * have permission to perform the write operation, throw [AccessDeniedException].
 *
 * - For write actions, if the user doesn't have permission to read the object or to perform the
 * write operation, throw [EntityNotFoundException].
 *
 * ## Naming convention
 *
 * The methods in this class are named with an eye toward the call sites looking like declarations
 * of the required permissions, rather than looking like imperative operations. They describe what
 * the caller is asking permission to do. That is, if you call [createFacility], you're expressing
 * that you intend to create a facility, but [createFacility] doesn't actually create anything.
 * Although that isn't consistent with method naming conventions in the application as a whole,
 * you'll always be calling this code from inside a [requirePermissions] block, so the context
 * should make it clear what's going on.
 */
class PermissionRequirements(private val user: UserModel) {
  fun createAccession(facilityId: FacilityId) {
    if (!user.canCreateAccession(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to create accessions in facility $facilityId")
    }
  }

  fun readAccession(accessionId: AccessionId) {
    if (!user.canReadAccession(accessionId)) {
      throw AccessionNotFoundException(accessionId)
    }
  }

  fun updateAccession(accessionId: AccessionId) {
    if (!user.canUpdateAccession(accessionId)) {
      readAccession(accessionId)
      throw AccessDeniedException("No permission to update accession $accessionId")
    }
  }

  fun createFacility(siteId: SiteId) {
    if (!user.canCreateFacility(siteId)) {
      readSite(siteId)
      throw AccessDeniedException("No permission to create facilities in site $siteId")
    }
  }

  fun readFacility(facilityId: FacilityId) {
    if (!user.canReadFacility(facilityId)) {
      throw FacilityNotFoundException(facilityId)
    }
  }

  fun createDevice(facilityId: FacilityId) {
    if (!user.canCreateDevice(facilityId)) {
      readFacility(facilityId)
      throw AccessDeniedException("No permission to create device in facility $facilityId")
    }
  }

  fun readDevice(deviceId: DeviceId) {
    if (!user.canReadDevice(deviceId)) {
      throw DeviceNotFoundException(deviceId)
    }
  }

  fun updateDevice(deviceId: DeviceId) {
    if (!user.canUpdateDevice(deviceId)) {
      readDevice(deviceId)
      throw AccessDeniedException("No permission to update device $deviceId")
    }
  }

  fun createLayer(siteId: SiteId) {
    if (!user.canCreateLayer(siteId)) {
      readSite(siteId)
      throw AccessDeniedException("No permission to create layer at site $siteId")
    }
  }

  fun readLayer(layerId: LayerId) {
    if (!user.canReadLayer(layerId)) {
      throw LayerNotFoundException(layerId)
    }
  }

  fun updateLayer(layerId: LayerId) {
    if (!user.canUpdateLayer(layerId)) {
      readLayer(layerId)
      throw AccessDeniedException("No permission to update layer $layerId")
    }
  }

  fun deleteLayer(layerId: LayerId) {
    if (!user.canDeleteLayer(layerId)) {
      readLayer(layerId)
      throw AccessDeniedException("No permission to delete layer $layerId")
    }
  }

  fun createLayerData(layerId: LayerId) {
    if (!user.canCreateLayerData(layerId)) {
      readLayer(layerId)
      throw AccessDeniedException("No permission to create data in layer $layerId")
    }
  }

  fun readFeature(featureId: FeatureId) {
    if (!user.canReadFeature(featureId)) {
      throw FeatureNotFoundException(featureId)
    }
  }

  fun updateFeature(featureId: FeatureId) {
    if (!user.canUpdateFeature(featureId)) {
      readFeature(featureId)
      throw AccessDeniedException("No permission to update feature $featureId")
    }
  }

  fun deleteFeature(featureId: FeatureId) {
    if (!user.canDeleteFeature(featureId)) {
      readFeature(featureId)
      throw AccessDeniedException("No permission to delete feature $featureId")
    }
  }

  fun readFeaturePhoto(photoId: PhotoId) {
    if (!user.canReadFeaturePhoto(photoId)) {
      throw PhotoNotFoundException(photoId)
    }
  }

  fun deleteFeaturePhoto(photoId: PhotoId) {
    if (!user.canDeleteFeaturePhoto(photoId)) {
      readFeaturePhoto(photoId)
      throw AccessDeniedException("No permission to delete feature photo $photoId")
    }
  }

  fun createSite(projectId: ProjectId) {
    if (!user.canCreateSite(projectId)) {
      readProject(projectId)
      throw AccessDeniedException("No permission to create sites in project $projectId")
    }
  }

  fun readSite(siteId: SiteId) {
    if (!user.canReadSite(siteId)) {
      throw SiteNotFoundException(siteId)
    }
  }

  fun createProject(organizationId: OrganizationId) {
    if (!user.canCreateProject(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to create projects in organization $organizationId")
    }
  }

  fun readProject(projectId: ProjectId) {
    if (!user.canReadProject(projectId)) {
      throw ProjectNotFoundException(projectId)
    }
  }

  fun listProjects(organizationId: OrganizationId) {
    if (!user.canListProjects(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to list projects in organization $organizationId")
    }
  }

  fun updateProject(projectId: ProjectId) {
    if (!user.canUpdateProject(projectId)) {
      readProject(projectId)
      throw AccessDeniedException("No permission to update project $projectId")
    }
  }

  fun addProjectUser(projectId: ProjectId) {
    if (!user.canAddProjectUser(projectId)) {
      readProject(projectId)
      throw AccessDeniedException("No permission to add users to project $projectId")
    }
  }

  fun removeProjectUser(projectId: ProjectId) {
    if (!user.canRemoveProjectUser(projectId)) {
      readProject(projectId)
      throw AccessDeniedException("No permission to remove users from project $projectId")
    }
  }

  fun readOrganization(organizationId: OrganizationId) {
    if (!user.canReadOrganization(organizationId)) {
      throw OrganizationNotFoundException(organizationId)
    }
  }

  fun addOrganizationUser(organizationId: OrganizationId) {
    if (!user.canAddOrganizationUser(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to add users to organization $organizationId")
    }
  }

  fun removeOrganizationUser(organizationId: OrganizationId) {
    if (!user.canRemoveOrganizationUser(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException("No permission to remove users from organization $organizationId")
    }
  }

  fun setOrganizationUserRole(organizationId: OrganizationId, role: Role) {
    if (!user.canSetOrganizationUserRole(organizationId, role)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to grant role to users in organization $organizationId")
    }
  }

  fun createApiKey(organizationId: OrganizationId) {
    if (!user.canCreateApiKey(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to create API keys in organization $organizationId")
    }
  }

  fun deleteApiKey(organizationId: OrganizationId) {
    if (!user.canDeleteApiKey(organizationId)) {
      readOrganization(organizationId)
      throw AccessDeniedException(
          "No permission to delete API keys from organization $organizationId")
    }
  }

  fun createSpecies() {
    if (!user.canCreateSpecies()) {
      throw AccessDeniedException("No permission to create species")
    }
  }

  fun deleteSpecies(speciesId: SpeciesId) {
    if (!user.canDeleteSpecies(speciesId)) {
      throw AccessDeniedException("No permission to delete species $speciesId")
    }
  }

  fun updateSpecies(speciesId: SpeciesId) {
    if (!user.canUpdateSpecies(speciesId)) {
      throw AccessDeniedException("No permission to update species $speciesId")
    }
  }

  fun createFamily() {
    if (!user.canCreateFamily()) {
      throw AccessDeniedException("No permission to create families")
    }
  }

  fun createTimeseries(deviceId: DeviceId) {
    if (!user.canCreateTimeseries(deviceId)) {
      readDevice(deviceId)
      throw AccessDeniedException("No permission to create timeseries for device $deviceId")
    }
  }
}
