package com.terraformation.backend.customer

import com.terraformation.backend.customer.db.FacilityStore
import com.terraformation.backend.customer.event.FacilityIdleEvent
import com.terraformation.backend.customer.event.FacilityTimeZoneChangedEvent
import com.terraformation.backend.customer.event.OrganizationTimeZoneChangedEvent
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.log.perClassLogger
import com.terraformation.backend.report.event.ReportSubmittedEvent
import jakarta.inject.Named
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
  private val log = perClassLogger()

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

  @EventListener
  fun on(event: ReportSubmittedEvent) {
    event.body.nurseries.forEach { nurseryBody ->
      val existing = facilityStore.fetchOneById(nurseryBody.id)
      val updated =
          existing.copy(
              buildCompletedDate = existing.buildCompletedDate ?: nurseryBody.buildCompletedDate,
              buildStartedDate = existing.buildStartedDate ?: nurseryBody.buildStartedDate,
              capacity = existing.capacity ?: nurseryBody.capacity,
              operationStartedDate = existing.operationStartedDate
                      ?: nurseryBody.operationStartedDate,
          )

      if (existing != updated) {
        log.info("Updating nursery ${updated.id} data from report")
        facilityStore.update(updated)
      }
    }

    event.body.seedBanks.forEach { seedBankBody ->
      val existing = facilityStore.fetchOneById(seedBankBody.id)
      val updated =
          existing.copy(
              buildCompletedDate = existing.buildCompletedDate ?: seedBankBody.buildCompletedDate,
              buildStartedDate = existing.buildStartedDate ?: seedBankBody.buildStartedDate,
              operationStartedDate = existing.operationStartedDate
                      ?: seedBankBody.operationStartedDate,
          )

      if (existing != updated) {
        log.info("Updating seed bank ${updated.id} data from report")
        facilityStore.update(updated)
      }
    }
  }

  companion object {
    private const val SCAN_FOR_IDLE_FACILITIES_JOB_NAME = "FacilityService.scanForIdleFacilities"
  }
}
