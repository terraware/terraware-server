package com.terraformation.backend.customer.model

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.AccessionId
import com.terraformation.backend.db.AccessionNotFoundException
import com.terraformation.backend.db.AutomationId
import com.terraformation.backend.db.AutomationNotFoundException
import com.terraformation.backend.db.DeviceId
import com.terraformation.backend.db.DeviceNotFoundException
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
import io.mockk.MockKMatcherScope
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

/**
 * Tests the exception-throwing logic in [PermissionRequirements].
 *
 * The general form of the test methods here is
 *
 * 1. Assert the exception that's thrown if the exception-checking method is called when the user
 * has none of the relevant permissions at all
 *
 * 2. For each additional exception that can be thrown (if any), grant the user a new permission and
 * assert that the alternate exception is thrown
 *
 * 3. Grant the final permission that allows the check to succeed
 *
 * 4. Call the permission checking method again; the test will fail if it throws an exception
 */
internal class PermissionRequirementsTest : RunsAsUser {
  override val user: UserModel = mockk(relaxed = true)
  private val requirements = PermissionRequirements(user)

  private val accessionId = AccessionId(1)
  private val automationId = AutomationId(1)
  private val deviceId = DeviceId(1)
  private val facilityId = FacilityId(1)
  private val featureId = FeatureId(1)
  private val layerId = LayerId(1)
  private val organizationId = OrganizationId(1)
  private val photoId = PhotoId(1)
  private val projectId = ProjectId(1)
  private val role = Role.CONTRIBUTOR
  private val siteId = SiteId(1)
  private val speciesId = SpeciesId(1)

  /**
   * Grants permission to perform a particular operation. This is a simple wrapper around a MockK
   * `every { user.canX() } returns true` call, but with a more concise syntax and a more meaningful
   * name.
   */
  private fun grant(stubBlock: MockKMatcherScope.() -> Boolean) {
    every(stubBlock) returns true
  }

  @Test
  fun createAccession() {
    assertThrows<FacilityNotFoundException> { requirements.createAccession(facilityId) }

    grant { user.canReadFacility(facilityId) }
    assertThrows<AccessDeniedException> { requirements.createAccession(facilityId) }

    grant { user.canCreateAccession(facilityId) }
    requirements.createAccession(facilityId)
  }

  @Test
  fun readAccession() {
    assertThrows<AccessionNotFoundException> { requirements.readAccession(accessionId) }

    grant { user.canReadAccession(accessionId) }
    requirements.readAccession(accessionId)
  }

  @Test
  fun updateAccession() {
    assertThrows<AccessionNotFoundException> { requirements.updateAccession(accessionId) }

    grant { user.canReadAccession(accessionId) }
    assertThrows<AccessDeniedException> { requirements.updateAccession(accessionId) }

    grant { user.canUpdateAccession(accessionId) }
    requirements.updateAccession(accessionId)
  }

  @Test
  fun createAutomation() {
    assertThrows<FacilityNotFoundException> { requirements.createAutomation(facilityId) }

    grant { user.canReadFacility(facilityId) }
    assertThrows<AccessDeniedException> { requirements.createAutomation(facilityId) }

    grant { user.canCreateAutomation(facilityId) }
    requirements.createAutomation(facilityId)
  }

  @Test
  fun listAutomations() {
    assertThrows<FacilityNotFoundException> { requirements.listAutomations(facilityId) }

    grant { user.canReadFacility(facilityId) }
    assertThrows<AccessDeniedException> { requirements.listAutomations(facilityId) }

    grant { user.canListAutomations(facilityId) }
    requirements.listAutomations(facilityId)
  }

  @Test
  fun readAutomation() {
    assertThrows<AutomationNotFoundException> { requirements.readAutomation(automationId) }

    grant { user.canReadAutomation(automationId) }
    requirements.readAutomation(automationId)
  }

  @Test
  fun updateAutomation() {
    assertThrows<AutomationNotFoundException> { requirements.updateAutomation(automationId) }

    grant { user.canReadAutomation(automationId) }
    assertThrows<AccessDeniedException> { requirements.updateAutomation(automationId) }

    grant { user.canUpdateAutomation(automationId) }
    requirements.updateAutomation(automationId)
  }

  @Test
  fun deleteAutomation() {
    assertThrows<AutomationNotFoundException> { requirements.deleteAutomation(automationId) }

    grant { user.canReadAutomation(automationId) }
    assertThrows<AccessDeniedException> { requirements.deleteAutomation(automationId) }

    grant { user.canDeleteAutomation(automationId) }
    requirements.deleteAutomation(automationId)
  }

  @Test
  fun createFacility() {
    assertThrows<SiteNotFoundException> { requirements.createFacility(siteId) }

    grant { user.canReadSite(siteId) }
    assertThrows<AccessDeniedException> { requirements.createFacility(siteId) }

    grant { user.canCreateFacility(siteId) }
    requirements.createFacility(siteId)
  }

  @Test
  fun readFacility() {
    assertThrows<FacilityNotFoundException> { requirements.readFacility(facilityId) }

    grant { user.canReadFacility(facilityId) }
    requirements.readFacility(facilityId)
  }

  @Test
  fun updateFacility() {
    assertThrows<FacilityNotFoundException> { requirements.updateFacility(facilityId) }

    grant { user.canReadFacility(facilityId) }
    assertThrows<AccessDeniedException> { requirements.updateFacility(facilityId) }

    grant { user.canUpdateFacility(facilityId) }
    requirements.updateFacility(facilityId)
  }

  @Test
  fun sendAlert() {
    assertThrows<FacilityNotFoundException> { requirements.sendAlert(facilityId) }

    grant { user.canReadFacility(facilityId) }
    assertThrows<AccessDeniedException> { requirements.sendAlert(facilityId) }

    grant { user.canSendAlert(facilityId) }
    requirements.sendAlert(facilityId)
  }

  @Test
  fun createDevice() {
    assertThrows<FacilityNotFoundException> { requirements.createDevice(facilityId) }

    grant { user.canReadFacility(facilityId) }
    assertThrows<AccessDeniedException> { requirements.createDevice(facilityId) }

    grant { user.canCreateDevice(facilityId) }
    requirements.createDevice(facilityId)
  }

  @Test
  fun readDevice() {
    assertThrows<DeviceNotFoundException> { requirements.readDevice(deviceId) }

    grant { user.canReadDevice(deviceId) }
    requirements.readDevice(deviceId)
  }

  @Test
  fun updateDevice() {
    assertThrows<DeviceNotFoundException> { requirements.updateDevice(deviceId) }

    grant { user.canReadDevice(deviceId) }
    assertThrows<AccessDeniedException> { requirements.updateDevice(deviceId) }

    grant { user.canUpdateDevice(deviceId) }
    requirements.updateDevice(deviceId)
  }

  @Test
  fun createLayer() {
    assertThrows<SiteNotFoundException> { requirements.createLayer(siteId) }

    grant { user.canReadSite(siteId) }
    assertThrows<AccessDeniedException> { requirements.createLayer(siteId) }

    grant { user.canCreateLayer(siteId) }
    requirements.createLayer(siteId)
  }

  @Test
  fun readLayer() {
    assertThrows<LayerNotFoundException> { requirements.readLayer(layerId) }

    grant { user.canReadLayer(layerId) }
    requirements.readLayer(layerId)
  }

  @Test
  fun updateLayer() {
    assertThrows<LayerNotFoundException> { requirements.updateLayer(layerId) }

    grant { user.canReadLayer(layerId) }
    assertThrows<AccessDeniedException> { requirements.updateLayer(layerId) }

    grant { user.canUpdateLayer(layerId) }
    requirements.updateLayer(layerId)
  }

  @Test
  fun deleteLayer() {
    assertThrows<LayerNotFoundException> { requirements.deleteLayer(layerId) }

    grant { user.canReadLayer(layerId) }
    assertThrows<AccessDeniedException> { requirements.updateLayer(layerId) }

    grant { user.canDeleteLayer(layerId) }
    requirements.deleteLayer(layerId)
  }

  @Test
  fun createFeature() {
    assertThrows<LayerNotFoundException> { requirements.createFeature(layerId) }

    grant { user.canReadLayer(layerId) }
    assertThrows<AccessDeniedException> { requirements.createFeature(layerId) }

    grant { user.canCreateFeature(layerId) }
    requirements.createFeature(layerId)
  }

  @Test
  fun readFeature() {
    assertThrows<FeatureNotFoundException> { requirements.readFeature(featureId) }

    grant { user.canReadFeature(featureId) }
    requirements.readFeature(featureId)
  }

  @Test
  fun updateFeature() {
    assertThrows<FeatureNotFoundException> { requirements.updateFeature(featureId) }

    grant { user.canReadFeature(featureId) }
    assertThrows<AccessDeniedException> { requirements.updateFeature(featureId) }

    grant { user.canUpdateFeature(featureId) }
    requirements.updateFeature(featureId)
  }

  @Test
  fun deleteFeature() {
    assertThrows<FeatureNotFoundException> { requirements.deleteFeature(featureId) }

    grant { user.canReadFeature(featureId) }
    assertThrows<AccessDeniedException> { requirements.deleteFeature(featureId) }

    grant { user.canDeleteFeature(featureId) }
    requirements.deleteFeature(featureId)
  }

  @Test
  fun readFeaturePhoto() {
    assertThrows<PhotoNotFoundException> { requirements.readFeaturePhoto(photoId) }

    grant { user.canReadFeaturePhoto(photoId) }
    requirements.readFeaturePhoto(photoId)
  }

  @Test
  fun deleteFeaturePhoto() {
    assertThrows<PhotoNotFoundException> { requirements.deleteFeaturePhoto(photoId) }

    grant { user.canReadFeaturePhoto(photoId) }
    assertThrows<AccessDeniedException> { requirements.deleteFeaturePhoto(photoId) }

    grant { user.canDeleteFeaturePhoto(photoId) }
    requirements.deleteFeaturePhoto(photoId)
  }

  @Test
  fun createSite() {
    assertThrows<ProjectNotFoundException> { requirements.createSite(projectId) }

    grant { user.canReadProject(projectId) }
    assertThrows<AccessDeniedException> { requirements.createSite(projectId) }

    grant { user.canCreateSite(projectId) }
    requirements.createSite(projectId)
  }

  @Test
  fun readSite() {
    assertThrows<SiteNotFoundException> { requirements.readSite(siteId) }

    grant { user.canReadSite(siteId) }
    requirements.readSite(siteId)
  }

  @Test
  fun createProject() {
    assertThrows<OrganizationNotFoundException> { requirements.createProject(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.createProject(organizationId) }

    grant { user.canCreateProject(organizationId) }
    requirements.createProject(organizationId)
  }

  @Test
  fun readProject() {
    assertThrows<ProjectNotFoundException> { requirements.readProject(projectId) }

    grant { user.canReadProject(projectId) }
    requirements.readProject(projectId)
  }

  @Test
  fun listProjects() {
    assertThrows<OrganizationNotFoundException> { requirements.listProjects(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.listProjects(organizationId) }

    grant { user.canListProjects(organizationId) }
    requirements.listProjects(organizationId)
  }

  @Test
  fun updateProject() {
    assertThrows<ProjectNotFoundException> { requirements.updateProject(projectId) }

    grant { user.canReadProject(projectId) }
    assertThrows<AccessDeniedException> { requirements.updateProject(projectId) }

    grant { user.canUpdateProject(projectId) }
    requirements.updateProject(projectId)
  }

  @Test
  fun addProjectUser() {
    assertThrows<ProjectNotFoundException> { requirements.addProjectUser(projectId) }

    grant { user.canReadProject(projectId) }
    assertThrows<AccessDeniedException> { requirements.addProjectUser(projectId) }

    grant { user.canAddProjectUser(projectId) }
    requirements.addProjectUser(projectId)
  }

  @Test
  fun removeProjectUser() {
    assertThrows<ProjectNotFoundException> { requirements.removeProjectUser(projectId) }

    grant { user.canReadProject(projectId) }
    assertThrows<AccessDeniedException> { requirements.removeProjectUser(projectId) }

    grant { user.canRemoveProjectUser(projectId) }
    requirements.removeProjectUser(projectId)
  }

  @Test
  fun readOrganization() {
    assertThrows<OrganizationNotFoundException> { requirements.readOrganization(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    requirements.readOrganization(organizationId)
  }

  @Test
  fun addOrganizationUser() {
    assertThrows<OrganizationNotFoundException> { requirements.addOrganizationUser(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.addOrganizationUser(organizationId) }

    grant { user.canAddOrganizationUser(organizationId) }
    requirements.addOrganizationUser(organizationId)
  }

  @Test
  fun removeOrganizationUser() {
    assertThrows<OrganizationNotFoundException> {
      requirements.removeOrganizationUser(organizationId)
    }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.removeOrganizationUser(organizationId) }

    grant { user.canRemoveOrganizationUser(organizationId) }
    requirements.removeOrganizationUser(organizationId)
  }

  @Test
  fun setOrganizationUserRole() {
    assertThrows<OrganizationNotFoundException> {
      requirements.setOrganizationUserRole(organizationId, role)
    }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> {
      requirements.setOrganizationUserRole(organizationId, role)
    }

    grant { user.canSetOrganizationUserRole(organizationId, role) }
    requirements.setOrganizationUserRole(organizationId, role)
  }

  @Test
  fun createApiKey() {
    assertThrows<OrganizationNotFoundException> { requirements.createApiKey(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.createApiKey(organizationId) }

    grant { user.canCreateApiKey(organizationId) }
    requirements.createApiKey(organizationId)
  }

  @Test
  fun deleteApiKey() {
    assertThrows<OrganizationNotFoundException> { requirements.deleteApiKey(organizationId) }

    grant { user.canReadOrganization(organizationId) }
    assertThrows<AccessDeniedException> { requirements.deleteApiKey(organizationId) }

    grant { user.canDeleteApiKey(organizationId) }
    requirements.deleteApiKey(organizationId)
  }

  @Test
  fun createSpecies() {
    assertThrows<AccessDeniedException> { requirements.createSpecies() }

    grant { user.canCreateSpecies() }
    requirements.createSpecies()
  }

  @Test
  fun deleteSpecies() {
    assertThrows<AccessDeniedException> { requirements.deleteSpecies(speciesId) }

    grant { user.canDeleteSpecies(speciesId) }
    requirements.deleteSpecies(speciesId)
  }

  @Test
  fun updateSpecies() {
    assertThrows<AccessDeniedException> { requirements.updateSpecies(speciesId) }

    grant { user.canUpdateSpecies(speciesId) }
    requirements.updateSpecies(speciesId)
  }

  @Test
  fun createTimeseries() {
    assertThrows<DeviceNotFoundException> { requirements.createTimeseries(deviceId) }

    grant { user.canReadDevice(deviceId) }
    assertThrows<AccessDeniedException> { requirements.createTimeseries(deviceId) }

    grant { user.canCreateTimeseries(deviceId) }
    requirements.createTimeseries(deviceId)
  }
}
