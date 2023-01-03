package com.terraformation.backend.customer

import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.FacilityTimeZoneChangedEvent
import com.terraformation.backend.customer.event.OrganizationTimeZoneChangedEvent
import com.terraformation.backend.customer.model.SystemUser
import javax.inject.Named
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.spring.annotations.Recurring
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener

/** Facility-related business logic that needs to interact with multiple services. */
@Named
class FacilityService(
    private val facilityStore: FacilityStore,
    private val publisher: ApplicationEventPublisher,
    private val systemUser: SystemUser,
) {
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

  /**
   * Publishes [FacilityTimeZoneChangedEvent]s for any facilities whose time zones have changed
   * implicitly thanks to a change of their owning organization's time zone.
   */
  @EventListener
  fun on(event: OrganizationTimeZoneChangedEvent) {
    facilityStore
        .fetchByOrganizationId(event.organizationId)
        .filter { it.timeZone == null }
        .forEach { facility -> publisher.publishEvent(FacilityTimeZoneChangedEvent(facility)) }
  }

  companion object {
    private const val SCAN_FOR_IDLE_FACILITIES_JOB_NAME = "FacilityService.scanForIdleFacilities"
  }
}
