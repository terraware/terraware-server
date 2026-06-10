package com.terraformation.backend.plantingmanagement.db

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.SubstratumHistoryId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.eventlog.db.EventLogStore
import com.terraformation.backend.eventlog.model.EventLogEntry
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonDeletedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonRelatedPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetUpdatedEventValues
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonUpdatedEventValues
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class PlantingSeasonNotificationsServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val objectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
  private val eventLogStore: EventLogStore by lazy {
    EventLogStore(clock, dslContext, objectMapper)
  }
  private val service: PlantingSeasonNotificationsService by lazy {
    PlantingSeasonNotificationsService(dslContext, eventLogStore)
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var stratumId1: StratumId
  private lateinit var substratumId1: SubstratumId
  private lateinit var plantingSeasonId: PlantingSeasonId
  private lateinit var speciesId1: SpeciesId
  private lateinit var speciesId2: SpeciesId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser(role = Role.Manager)
    plantingSiteId = insertPlantingSite()
    stratumId1 = insertStratum()
    substratumId1 = insertSubstratum()
    plantingSeasonId = insertPlantingSeason()
    speciesId1 = insertSpecies()
    speciesId2 = insertSpecies()
  }

  @Nested
  inner class GetNotificationsByPlantingSeason {
    @Test
    fun `returns all events when the season has never dismissed a notification`() {
      val created = insertCreatedEvent()
      val deleted = insertDeletedEvent()

      assertEquals(listOf(created.event, deleted.event), service.getNotifications(plantingSeasonId))
    }

    @Test
    fun `returns only events logged after the dismissal watermark`() {
      val dismissed = insertCreatedEvent()
      val newer = insertCreatedEvent(name = "Newer")
      val newest = insertDeletedEvent()
      insertPlantingSeasonNotification(lastDismissedEventLogId = dismissed.id)

      assertEquals(listOf(newer.event, newest.event), service.getNotifications(plantingSeasonId))
    }

    @Test
    fun `returns a single combined update event per season`() {
      insertUpdatedEvent(
          changedFrom = PlantingSeasonUpdatedEventValues(name = "A"),
          changedTo = PlantingSeasonUpdatedEventValues(name = "B"),
      )
      clock.instant = clock.instant.plusSeconds(1)
      val second =
          insertUpdatedEvent(
              changedFrom = PlantingSeasonUpdatedEventValues(name = "B"),
              changedTo = PlantingSeasonUpdatedEventValues(name = "C"),
          )

      val combined =
          (second.event as PlantingSeasonUpdatedEvent).copy(
              changedFrom = PlantingSeasonUpdatedEventValues(name = "A")
          )

      assertEquals(listOf(combined), service.getNotifications(plantingSeasonId))
    }

    @Test
    fun `excludes events for other planting seasons`() {
      val otherSeasonId = insertPlantingSeason()
      val expected = insertCreatedEvent()
      insertCreatedEvent(plantingSeasonId = otherSeasonId)

      assertEquals(listOf(expected.event), service.getNotifications(plantingSeasonId))
    }

    @Test
    fun `returns empty list when there are no undismissed events`() {
      val dismissed = insertCreatedEvent()
      insertPlantingSeasonNotification(lastDismissedEventLogId = dismissed.id)

      assertEquals(
          emptyList<PlantingSeasonRelatedPersistentEvent>(),
          service.getNotifications(plantingSeasonId),
      )
    }

    @Test
    fun `throws exception when user has no permission to read the planting season`() {
      deleteOrganizationUser()

      assertThrows<PlantingSeasonNotFoundException> { service.getNotifications(plantingSeasonId) }
    }
  }

  @Nested
  inner class GetNotificationsByOrganization {
    @Test
    fun `groups undismissed events by planting season`() {
      val otherSeasonId = insertPlantingSeason()
      val firstSeasonEvent = insertCreatedEvent()
      val otherSeasonEvent = insertCreatedEvent(plantingSeasonId = otherSeasonId)

      assertEquals(
          mapOf(
              plantingSeasonId to listOf(firstSeasonEvent.event),
              otherSeasonId to listOf(otherSeasonEvent.event),
          ),
          service.getNotifications(organizationId),
      )
    }

    @Test
    fun `applies each season's dismissal watermark independently`() {
      val otherSeasonId = insertPlantingSeason()
      val dismissed = insertCreatedEvent()
      val firstSeasonNewer = insertCreatedEvent(name = "Newer")
      val otherSeasonEvent = insertCreatedEvent(plantingSeasonId = otherSeasonId)
      insertPlantingSeasonNotification(
          plantingSeasonId = plantingSeasonId,
          lastDismissedEventLogId = dismissed.id,
      )

      assertEquals(
          mapOf(
              plantingSeasonId to listOf(firstSeasonNewer.event),
              otherSeasonId to listOf(otherSeasonEvent.event),
          ),
          service.getNotifications(organizationId),
      )
    }

    @Test
    fun `excludes events from other organizations`() {
      val expected = insertCreatedEvent()

      val otherOrganizationId = insertOrganization()
      val otherSiteId = insertPlantingSite(organizationId = otherOrganizationId)
      val otherSeasonId = insertPlantingSeason(plantingSiteId = otherSiteId)
      insertCreatedEvent(
          organizationId = otherOrganizationId,
          plantingSiteId = otherSiteId,
          plantingSeasonId = otherSeasonId,
      )

      assertEquals(
          mapOf(plantingSeasonId to listOf(expected.event)),
          service.getNotifications(organizationId),
      )
    }

    @Test
    fun `returns empty map when the organization has no planting seasons`() {
      val emptyOrganizationId = insertOrganization()

      assertEquals(
          emptyMap<PlantingSeasonId, List<PlantingSeasonRelatedPersistentEvent>>(),
          service.getNotifications(emptyOrganizationId),
      )
    }

    @Test
    fun `throws exception when user lacks permission for a season in the organization`() {
      deleteOrganizationUser()

      assertThrows<PlantingSeasonNotFoundException> { service.getNotifications(organizationId) }
    }
  }

  @Nested
  inner class CombineEvents {
    @Test
    fun `merges correctly and keeps non-update events`() {
      val firstStart = LocalDate.of(2024, 1, 1)
      val secondStart = LocalDate.of(2024, 2, 1)
      val firstEnd = LocalDate.of(2024, 6, 1)
      val secondEnd = LocalDate.of(2024, 7, 1)

      val created = insertCreatedEvent()
      insertUpdatedEvent(
          changedFrom = PlantingSeasonUpdatedEventValues(name = "A", startDate = firstStart),
          changedTo = PlantingSeasonUpdatedEventValues(name = "B", startDate = secondStart),
      )
      clock.instant = clock.instant.plusSeconds(1)
      val second =
          insertUpdatedEvent(
              changedFrom =
                  PlantingSeasonUpdatedEventValues(
                      endDate = firstEnd,
                      status = PlantingSeasonStatus.Upcoming,
                  ),
              changedTo =
                  PlantingSeasonUpdatedEventValues(
                      endDate = secondEnd,
                      status = PlantingSeasonStatus.Active,
                  ),
          )
      clock.instant = clock.instant.plusSeconds(1)
      val third =
          insertUpdatedEvent(
              changedFrom = PlantingSeasonUpdatedEventValues(status = PlantingSeasonStatus.Active),
              changedTo = PlantingSeasonUpdatedEventValues(status = PlantingSeasonStatus.Closed),
          )

      val combined =
          (second.event as PlantingSeasonUpdatedEvent).copy(
              changedFrom =
                  PlantingSeasonUpdatedEventValues(
                      endDate = firstEnd,
                      name = "A",
                      startDate = firstStart,
                      status = PlantingSeasonStatus.Upcoming,
                  ),
              changedTo =
                  PlantingSeasonUpdatedEventValues(
                      endDate = secondEnd,
                      name = "B",
                      startDate = secondStart,
                      status = PlantingSeasonStatus.Active,
                  ),
          )

      assertEquals(
          listOf(created.event, combined, third.event),
          service.getNotifications(plantingSeasonId),
      )
    }

    @Test
    fun `leaves a single update event unchanged`() {
      val update =
          insertUpdatedEvent(
              changedFrom = PlantingSeasonUpdatedEventValues(name = "A"),
              changedTo = PlantingSeasonUpdatedEventValues(name = "B"),
          )

      assertEquals(listOf(update.event), service.getNotifications(plantingSeasonId))
    }
  }

  @Nested
  inner class CombineSpeciesTargetEvents {
    @Test
    fun `groups season and species target events together`() {
      val seasonCreated = insertCreatedEvent()
      val targetCreated = insertSpeciesTargetCreatedEvent()

      assertEquals(
          listOf(seasonCreated.event, targetCreated.event),
          service.getNotifications(plantingSeasonId),
      )
    }

    @Test
    fun `combines updates to the same species and substratum keeping earliest changedFrom and latest changedTo`() {
      insertSpeciesTargetUpdatedEvent(
          speciesId = speciesId1,
          changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 5),
          changedTo = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 7),
      )
      clock.instant = clock.instant.plusSeconds(1)
      val second =
          insertSpeciesTargetUpdatedEvent(
              speciesId = speciesId1,
              changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 7),
              changedTo = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 9),
          )

      val combined =
          (second.event as PlantingSeasonSpeciesTargetUpdatedEvent).copy(
              changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 5)
          )

      assertEquals(listOf(combined), service.getNotifications(plantingSeasonId))
    }

    @Test
    fun `does not combine updates for different species`() {
      val first =
          insertSpeciesTargetUpdatedEvent(
              speciesId = speciesId1,
              changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 5),
              changedTo = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 7),
          )
      clock.instant = clock.instant.plusSeconds(1)
      val second =
          insertSpeciesTargetUpdatedEvent(
              speciesId = speciesId2,
              changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 0),
              changedTo = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 3),
          )

      assertEquals(listOf(first.event, second.event), service.getNotifications(plantingSeasonId))
    }

    @Test
    fun `does not combine updates for different substrata`() {
      val otherSubstratumId = insertSubstratum()
      val first =
          insertSpeciesTargetUpdatedEvent(
              substratumId = substratumId1,
              changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 5),
              changedTo = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 7),
          )
      clock.instant = clock.instant.plusSeconds(1)
      val second =
          insertSpeciesTargetUpdatedEvent(
              substratumId = otherSubstratumId,
              changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 1),
              changedTo = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 4),
          )

      assertEquals(listOf(first.event, second.event), service.getNotifications(plantingSeasonId))
    }

    @Test
    fun `leaves a single species target update unchanged`() {
      val update =
          insertSpeciesTargetUpdatedEvent(
              changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 5),
              changedTo = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 7),
          )

      assertEquals(listOf(update.event), service.getNotifications(plantingSeasonId))
    }

    @Test
    fun `combines each event type independently within a season`() {
      insertUpdatedEvent(
          changedFrom = PlantingSeasonUpdatedEventValues(name = "A"),
          changedTo = PlantingSeasonUpdatedEventValues(name = "B"),
      )
      insertSpeciesTargetUpdatedEvent(
          speciesId = speciesId1,
          changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 5),
          changedTo = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 7),
      )
      clock.instant = clock.instant.plusSeconds(1)
      val seasonUpdate2 =
          insertUpdatedEvent(
              changedFrom = PlantingSeasonUpdatedEventValues(name = "B"),
              changedTo = PlantingSeasonUpdatedEventValues(name = "C"),
          )
      val targetUpdate2 =
          insertSpeciesTargetUpdatedEvent(
              speciesId = speciesId1,
              changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 7),
              changedTo = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 9),
          )

      val combinedSeason =
          (seasonUpdate2.event as PlantingSeasonUpdatedEvent).copy(
              changedFrom = PlantingSeasonUpdatedEventValues(name = "A")
          )
      val combinedTarget =
          (targetUpdate2.event as PlantingSeasonSpeciesTargetUpdatedEvent).copy(
              changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 5)
          )

      assertEquals(
          listOf(combinedSeason, combinedTarget),
          service.getNotifications(plantingSeasonId),
      )
    }
  }

  private fun insertUpdatedEvent(
      changedFrom: PlantingSeasonUpdatedEventValues = PlantingSeasonUpdatedEventValues(),
      changedTo: PlantingSeasonUpdatedEventValues = PlantingSeasonUpdatedEventValues(),
      plantingSeasonId: PlantingSeasonId = this.plantingSeasonId,
      plantingSiteId: PlantingSiteId = this.plantingSiteId,
      organizationId: OrganizationId = this.organizationId,
  ): EventLogEntry<PlantingSeasonRelatedPersistentEvent> {
    val event =
        PlantingSeasonUpdatedEvent(
            changedFrom = changedFrom,
            changedTo = changedTo,
            organizationId = organizationId,
            plantingSeasonId = plantingSeasonId,
            plantingSiteId = plantingSiteId,
        )

    return EventLogEntry(user.userId, clock.instant, event, eventLogStore.insertEvent(event))
  }

  private fun insertCreatedEvent(
      plantingSeasonId: PlantingSeasonId = this.plantingSeasonId,
      name: String = "Season",
      plantingSiteId: PlantingSiteId = this.plantingSiteId,
      organizationId: OrganizationId = this.organizationId,
  ): EventLogEntry<PlantingSeasonRelatedPersistentEvent> {
    val event =
        PlantingSeasonCreatedEvent(
            endDate = LocalDate.EPOCH.plusDays(1),
            name = name,
            organizationId = organizationId,
            plantingSeasonId = plantingSeasonId,
            plantingSiteId = plantingSiteId,
            startDate = LocalDate.EPOCH,
            status = PlantingSeasonStatus.Upcoming,
        )

    return EventLogEntry(user.userId, clock.instant, event, eventLogStore.insertEvent(event))
  }

  private fun insertDeletedEvent(
      plantingSeasonId: PlantingSeasonId = this.plantingSeasonId,
      plantingSiteId: PlantingSiteId = this.plantingSiteId,
      organizationId: OrganizationId = this.organizationId,
  ): EventLogEntry<PlantingSeasonRelatedPersistentEvent> {
    val event =
        PlantingSeasonDeletedEvent(
            organizationId = organizationId,
            plantingSeasonId = plantingSeasonId,
            plantingSiteId = plantingSiteId,
        )

    return EventLogEntry(user.userId, clock.instant, event, eventLogStore.insertEvent(event))
  }

  private fun insertSpeciesTargetCreatedEvent(
      speciesId: SpeciesId = speciesId1,
      quantity: Int = 1,
      substratumId: SubstratumId = substratumId1,
      plantingSeasonId: PlantingSeasonId = this.plantingSeasonId,
      plantingSiteId: PlantingSiteId = this.plantingSiteId,
      organizationId: OrganizationId = this.organizationId,
  ): EventLogEntry<PlantingSeasonRelatedPersistentEvent> {
    val event =
        PlantingSeasonSpeciesTargetCreatedEvent(
            organizationId = organizationId,
            plantingSeasonId = plantingSeasonId,
            plantingSiteId = plantingSiteId,
            quantity = quantity,
            speciesId = speciesId,
            stratumName = "Stratum",
            substratumHistoryId = SubstratumHistoryId(substratumId.value),
            substratumId = substratumId,
            substratumName = "Substratum",
        )

    return EventLogEntry(user.userId, clock.instant, event, eventLogStore.insertEvent(event))
  }

  private fun insertSpeciesTargetUpdatedEvent(
      speciesId: SpeciesId = speciesId1,
      changedFrom: PlantingSeasonSpeciesTargetUpdatedEventValues =
          PlantingSeasonSpeciesTargetUpdatedEventValues(),
      changedTo: PlantingSeasonSpeciesTargetUpdatedEventValues =
          PlantingSeasonSpeciesTargetUpdatedEventValues(),
      substratumId: SubstratumId = substratumId1,
      plantingSeasonId: PlantingSeasonId = this.plantingSeasonId,
      plantingSiteId: PlantingSiteId = this.plantingSiteId,
      organizationId: OrganizationId = this.organizationId,
  ): EventLogEntry<PlantingSeasonRelatedPersistentEvent> {
    val event =
        PlantingSeasonSpeciesTargetUpdatedEvent(
            changedFrom = changedFrom,
            changedTo = changedTo,
            organizationId = organizationId,
            plantingSeasonId = plantingSeasonId,
            plantingSiteId = plantingSiteId,
            speciesId = speciesId,
            stratumName = "Stratum",
            substratumHistoryId = SubstratumHistoryId(substratumId.value),
            substratumId = substratumId,
            substratumName = "Substratum",
        )

    return EventLogEntry(user.userId, clock.instant, event, eventLogStore.insertEvent(event))
  }
}
