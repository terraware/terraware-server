package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.tables.references.ORGANIZATIONS
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.event.PlantingSeasonPastEndDateEvent
import com.terraformation.backend.tracking.event.PlantingSeasonStartedEvent
import com.terraformation.backend.util.toInstant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

internal class PlantingSeasonStoreTransitionTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store: PlantingSeasonStore by lazy {
    PlantingSeasonStore(
        clock,
        dslContext,
        eventPublisher,
        ParentStore(dslContext),
        SeasonHelper(dslContext),
    )
  }

  private lateinit var timeZone: ZoneId
  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    timeZone = ZoneId.of("Pacific/Honolulu")
  }

  @Test
  fun `uses site timezone for determining when season becomes active`() {
    val startDate = LocalDate.EPOCH.plusDays(10)
    val endDate = startDate.plusDays(60)

    val plantingSiteId = insertPlantingSite(timeZone = timeZone)
    val plantingSeasonId =
        insertPlantingSeason(
            startDate = startDate,
            endDate = endDate,
            status = PlantingSeasonStatus.Upcoming,
            plantingSiteId = plantingSiteId,
        )

    // Pacific/Honolulu is UTC-10, so midnight there = 10:00 UTC.
    // At 09:59:59 UTC on start_date, it is still the previous day in Honolulu.
    clock.instant = startDate.toInstant(timeZone).minusSeconds(1)

    store.transitionPlantingSeasons()

    assertEquals(
        PlantingSeasonStatus.Upcoming,
        plantingSeasonsDao.fetchById(plantingSeasonId).first().statusId,
        "Season should be Upcoming before midnight in site timezone",
    )
    eventPublisher.assertEventNotPublished<PlantingSeasonStartedEvent>()

    clock.instant = startDate.toInstant(timeZone)

    store.transitionPlantingSeasons()

    assertEquals(
        PlantingSeasonStatus.Active,
        plantingSeasonsDao.fetchById(plantingSeasonId).first().statusId,
        "Season should be Active at midnight in site timezone",
    )
    eventPublisher.assertEventPublished(
        PlantingSeasonStartedEvent(plantingSiteId, plantingSeasonId)
    )

    clock.instant = endDate.plusDays(1).toInstant(timeZone)

    store.transitionPlantingSeasons()

    assertEquals(
        PlantingSeasonStatus.PastEndDate,
        plantingSeasonsDao.fetchById(plantingSeasonId).first().statusId,
        "Season should be PastEndDate after end date in site timezone",
    )
    eventPublisher.assertEventPublished(
        PlantingSeasonPastEndDateEvent(plantingSiteId, plantingSeasonId)
    )

    clock.instant = endDate.plusDays(2).toInstant(timeZone)
    eventPublisher.clear()

    store.transitionPlantingSeasons()
    eventPublisher.assertNoEventsPublished(
        "Should not publish any events if planting season already in correct state"
    )
  }

  @Test
  fun `uses org timezone when no site timezone for determining when season becomes active`() {
    val startDate = LocalDate.EPOCH.plusDays(10)
    val endDate = startDate.plusDays(60)

    dslContext.deleteFrom(ORGANIZATIONS).where(ORGANIZATIONS.ID.eq(organizationId))
    insertOrganization(timeZone = timeZone)
    val plantingSiteId = insertPlantingSite()
    val plantingSeasonId =
        insertPlantingSeason(
            startDate = startDate,
            endDate = endDate,
            status = PlantingSeasonStatus.Upcoming,
            plantingSiteId = plantingSiteId,
        )

    // Pacific/Honolulu is UTC-10, so midnight there = 10:00 UTC.
    // At 09:59:59 UTC on start_date, it is still the previous day in Honolulu.
    clock.instant = startDate.toInstant(timeZone).minusSeconds(1)

    store.transitionPlantingSeasons()

    assertEquals(
        PlantingSeasonStatus.Upcoming,
        plantingSeasonsDao.fetchById(plantingSeasonId).first().statusId,
        "Season should be Upcoming before midnight in org timezone",
    )
    eventPublisher.assertEventNotPublished<PlantingSeasonStartedEvent>()

    clock.instant = startDate.toInstant(timeZone)

    store.transitionPlantingSeasons()

    assertEquals(
        PlantingSeasonStatus.Active,
        plantingSeasonsDao.fetchById(plantingSeasonId).first().statusId,
        "Season should be Active at midnight in org timezone",
    )
    eventPublisher.assertEventPublished(
        PlantingSeasonStartedEvent(plantingSiteId, plantingSeasonId)
    )

    clock.instant = endDate.plusDays(1).toInstant(timeZone)

    store.transitionPlantingSeasons()

    assertEquals(
        PlantingSeasonStatus.PastEndDate,
        plantingSeasonsDao.fetchById(plantingSeasonId).first().statusId,
        "Season should be PastEndDate after end date in org timezone",
    )
    eventPublisher.assertEventPublished(
        PlantingSeasonPastEndDateEvent(plantingSiteId, plantingSeasonId)
    )

    clock.instant = endDate.plusDays(2).toInstant(timeZone)
    eventPublisher.clear()

    store.transitionPlantingSeasons()
    eventPublisher.assertNoEventsPublished(
        "Should not publish any events if planting season already in correct state"
    )
  }
}
