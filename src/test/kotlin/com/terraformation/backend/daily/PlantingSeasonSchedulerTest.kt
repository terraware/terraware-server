package com.terraformation.backend.daily

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.config.TerrawareServerConfig
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.mockUser
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.event.PlantingSeasonNotScheduledNotificationEvent
import com.terraformation.backend.tracking.event.PlantingSeasonNotScheduledSupportNotificationEvent
import com.terraformation.backend.util.toInstant
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PlantingSeasonSchedulerTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val config = mockk<TerrawareServerConfig>()
  private val eventPublisher = TestEventPublisher()

  private val scheduler: PlantingSeasonScheduler by lazy {
    PlantingSeasonScheduler(
        config,
        eventPublisher,
        PlantingSiteStore(
            clock,
            TestSingletons.countryDetector,
            dslContext,
            eventPublisher,
            IdentifierGenerator(clock, dslContext),
            monitoringPlotsDao,
            ParentStore(dslContext),
            plantingSeasonsDao,
            plantingSitesDao,
            substrataDao,
            strataDao,
            eventPublisher,
        ),
        SystemUser(usersDao),
    )
  }

  private val timeZone = ZoneId.of("America/Los_Angeles")
  private val initialDate = LocalDate.of(2023, 1, 1)
  private val initialInstant = initialDate.toInstant(timeZone)

  @BeforeEach
  fun setUp() {
    insertOrganization(timeZone = timeZone)

    clock.instant = initialInstant
  }

  @Nested
  inner class FirstPlantingSeasonNotification {
    @Test
    fun `sends reminders after correct numbers of weeks have passed since site creation`() {
      val plantingSiteId = insertPlantingSiteWithSubzone()

      assertEventsAtWeekNumber(0, PlantingSeasonNotScheduledNotificationEvent(plantingSiteId, 1))
      assertEventsAtWeekNumber(4, PlantingSeasonNotScheduledNotificationEvent(plantingSiteId, 2))
      assertNoEventsAtWeekNumber(52)
    }

    @Test
    fun `does not send first-season reminders about sites that have seasons`() {
      insertPlantingSiteWithSeason()

      assertNoEventsAtWeekNumber(0)
    }

    @Test
    fun `does not send multiple reminders if several reminder deadlines have passed`() {
      val plantingSiteId = insertPlantingSiteWithSubzone()

      clock.instant = initialDate.plusWeeks(52).toInstant(timeZone)
      scheduler.transitionPlantingSeasons()

      eventPublisher.assertExactEventsPublished(
          listOf(PlantingSeasonNotScheduledNotificationEvent(plantingSiteId, 2)),
          "Should have sent last notification",
      )

      clock.instant = clock.instant.plusSeconds(1)
      eventPublisher.clear()
      scheduler.transitionPlantingSeasons()

      eventPublisher.assertNoEventsPublished()
    }

    @Test
    fun `does not send reminders about sites without subzones`() {
      insertPlantingSite(createdTime = initialInstant)

      assertNoEventsAtWeekNumber(52)
    }

    @Test
    fun `sends reminders about partially-planted sites`() {
      val plantingSiteId = insertPlantingSiteWithSubzone()
      insertSubstratum(plantingCompletedTime = initialInstant)

      assertEventsAtWeekNumber(0, PlantingSeasonNotScheduledNotificationEvent(plantingSiteId, 1))
    }

    @Test
    fun `does not send reminders about fully-planted sites`() {
      insertPlantingSite(createdTime = initialInstant)
      insertStratum()
      insertSubstratum(plantingCompletedTime = initialInstant)
      insertStratum()
      insertSubstratum(plantingCompletedTime = initialInstant)

      assertNoEventsAtWeekNumber(52)
    }

    @Test
    fun `sends support notifications for accelerator planting sites`() {
      val plantingSiteId = insertPlantingSiteWithSubzone()
      insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)

      assertNoEventsBeforeWeekNumber(
          6,
          PlantingSeasonNotScheduledSupportNotificationEvent::class.java,
      )
      assertEventsAtWeekNumber(
          6,
          PlantingSeasonNotScheduledSupportNotificationEvent(plantingSiteId, 1),
      )
      assertEventsAtWeekNumber(
          14,
          PlantingSeasonNotScheduledSupportNotificationEvent(plantingSiteId, 2),
      )
      assertNoEventsAtWeekNumber(52)
    }
  }

  @Nested
  inner class NextPlantingSeasonNotification {
    @Test
    fun `sends reminders after correct numbers of weeks have passed since end of prior season`() {
      val plantingSiteId = insertPlantingSiteWithSeason()

      assertEventsAtWeekNumber(2, PlantingSeasonNotScheduledNotificationEvent(plantingSiteId, 1))
      assertEventsAtWeekNumber(6, PlantingSeasonNotScheduledNotificationEvent(plantingSiteId, 2))
      assertNoEventsAtWeekNumber(52)
    }

    @Test
    fun `does not send reminders if there are upcoming seasons`() {
      insertPlantingSiteWithSeason()
      insertPlantingSeason(
          timeZone = timeZone,
          startDate = initialDate.plusMonths(1),
          endDate = initialDate.plusMonths(3),
      )

      assertNoEventsAtWeekNumber(2)
    }

    @Test
    fun `does not send multiple reminders if several reminder deadlines have passed`() {
      val plantingSiteId = insertPlantingSiteWithSeason()

      clock.instant = instantAtWeekNumber(52)
      scheduler.transitionPlantingSeasons()

      eventPublisher.assertExactEventsPublished(
          listOf(PlantingSeasonNotScheduledNotificationEvent(plantingSiteId, 2)),
          "Should have sent last notification",
      )

      clock.instant = clock.instant.plusSeconds(1)
      eventPublisher.clear()
      scheduler.transitionPlantingSeasons()

      eventPublisher.assertNoEventsPublished()
    }

    @Test
    fun `does not send reminders about sites without subzones`() {
      insertPlantingSiteWithSeason(subzone = false)

      assertNoEventsAtWeekNumber(52)
    }

    @Test
    fun `sends reminders about partially-planted sites`() {
      val plantingSiteId = insertPlantingSiteWithSeason()
      insertSubstratum(plantingCompletedTime = initialInstant)

      assertEventsAtWeekNumber(2, PlantingSeasonNotScheduledNotificationEvent(plantingSiteId, 1))
    }

    @Test
    fun `does not send reminders about fully-planted sites`() {
      insertPlantingSite(createdTime = initialInstant)
      insertStratum()
      insertSubstratum(plantingCompletedTime = initialInstant)
      insertStratum()
      insertSubstratum(plantingCompletedTime = initialInstant)
      insertPlantingSeason(
          timeZone = timeZone,
          startDate = initialDate.minusWeeks(6),
          endDate = initialDate.minusDays(1),
      )

      assertNoEventsAtWeekNumber(52)
    }

    @Test
    fun `sends support notifications for accelerator planting sites`() {
      val plantingSiteId = insertPlantingSiteWithSeason()
      insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)

      assertNoEventsBeforeWeekNumber(
          8,
          PlantingSeasonNotScheduledSupportNotificationEvent::class.java,
      )
      assertEventsAtWeekNumber(
          8,
          PlantingSeasonNotScheduledSupportNotificationEvent(plantingSiteId, 1),
      )
      assertEventsAtWeekNumber(
          16,
          PlantingSeasonNotScheduledSupportNotificationEvent(plantingSiteId, 2),
      )
      assertNoEventsAtWeekNumber(52)
    }
  }

  private fun instantAtWeekNumber(weeks: Int) = initialInstant.plus(weeks * 7L, ChronoUnit.DAYS)

  private fun assertNoEventsAtWeekNumber(weeks: Int) {
    clock.instant = instantAtWeekNumber(weeks)

    scheduler.transitionPlantingSeasons()
    eventPublisher.assertNoEventsPublished("Sent unexpected notifications at $weeks weeks")
  }

  private fun assertNoEventsBeforeWeekNumber(weeks: Int, clazz: Class<*>) {
    clock.instant = instantAtWeekNumber(weeks).minusSeconds(1)

    scheduler.transitionPlantingSeasons()
    eventPublisher.assertEventNotPublished(clazz, "Sent unexpected ${clazz.name} at $weeks weeks")
    eventPublisher.clear()
  }

  private fun assertEventsAtWeekNumber(weeks: Int, vararg events: Any) {
    val targetInstant = instantAtWeekNumber(weeks)

    clock.instant = targetInstant.minusSeconds(1)
    scheduler.transitionPlantingSeasons()
    eventPublisher.assertNoEventsPublished("Sent $weeks-weeks notification too early")

    clock.instant = targetInstant
    scheduler.transitionPlantingSeasons()
    eventPublisher.assertExactEventsPublished(events.toSet(), "$weeks-weeks notification")
    eventPublisher.clear()

    clock.instant = targetInstant.plusSeconds(1)
    scheduler.transitionPlantingSeasons()
    eventPublisher.assertNoEventsPublished("Sent duplicate $weeks-weeks notification")
  }

  private fun insertPlantingSiteWithSubzone(): PlantingSiteId {
    val plantingSiteId = insertPlantingSite(createdTime = clock.instant)
    insertStratum()
    insertSubstratum()

    return plantingSiteId
  }

  private fun insertPlantingSiteWithSeason(subzone: Boolean = true): PlantingSiteId {
    val plantingSiteId =
        if (subzone) {
          insertPlantingSiteWithSubzone()
        } else {
          insertPlantingSite(createdTime = clock.instant)
        }

    // The end date is the day before initialDate so that calculating week numbers based on
    // initialDate will give us weeks since the end of the last season.
    insertPlantingSeason(
        plantingSiteId = plantingSiteId,
        timeZone = timeZone,
        startDate = initialDate.minusWeeks(6),
        endDate = initialDate.minusDays(1),
    )

    return plantingSiteId
  }
}
