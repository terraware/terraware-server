package com.terraformation.backend.customer

import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.model.SystemUser
import javax.annotation.ManagedBean
import org.jobrunr.jobs.annotations.Job
import org.jobrunr.spring.annotations.Recurring
import org.springframework.context.ApplicationEventPublisher

/** Facility-related business logic that needs to interact with multiple services. */
@ManagedBean
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

  companion object {
    private const val SCAN_FOR_IDLE_FACILITIES_JOB_NAME = "FacilityService.scanForIdleFacilities"
  }
}
