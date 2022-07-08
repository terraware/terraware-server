package com.terraformation.backend.customer

import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.db.ProjectStore
import com.terraformation.backend.customer.db.SiteStore
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.model.FacilityModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.FacilityType
import com.terraformation.backend.db.OrganizationHasNoSitesException
import com.terraformation.backend.db.OrganizationId
import javax.annotation.ManagedBean
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.spring.annotations.Recurring
import org.springframework.context.ApplicationEventPublisher

/** Facility-related business logic that needs to interact with multiple services. */
@ManagedBean
class FacilityService(
    private val facilityStore: FacilityStore,
    private val projectStore: ProjectStore,
    private val publisher: ApplicationEventPublisher,
    private val siteStore: SiteStore,
    private val systemUser: SystemUser,
) {
  /**
   * Creates a new facility under the default organization-wide project that is automatically
   * created for new organizations.
   */
  fun create(
      organizationId: OrganizationId,
      type: FacilityType,
      name: String,
      description: String? = null,
      maxIdleMinutes: Int = FacilityStore.DEFAULT_MAX_IDLE_MINUTES,
      createStorageLocations: Boolean = true,
  ): FacilityModel {
    val projectId =
        projectStore.fetchByOrganization(organizationId).firstOrNull { it.organizationWide }?.id
            ?: throw OrganizationHasNoSitesException(organizationId)
    val siteId =
        siteStore.fetchByProjectId(projectId).firstOrNull()?.id
            ?: throw OrganizationHasNoSitesException(organizationId)

    return facilityStore.create(
        siteId, type, name, description, maxIdleMinutes, createStorageLocations)
  }

  /** Sends alert email when facilities go idle. Runs once per minute. */
  @Job(name = SCAN_FOR_IDLE_FACILITIES_JOB_NAME, retries = 0)
  @Recurring(id = SCAN_FOR_IDLE_FACILITIES_JOB_NAME, cron = "* * * * *")
  fun scanForIdleFacilities() {
    systemUser.run {
      facilityStore.withIdleFacilities { facilityIds ->
        facilityIds.map { FacilityIdleEvent(it) }.forEach { publisher.publishEvent(it) }
      }
    }
  }

  companion object {
    private const val SCAN_FOR_IDLE_FACILITIES_JOB_NAME = "FacilityService.scanForIdleFacilities"
  }
}
