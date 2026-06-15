package com.terraformation.backend.plantingmanagement.db

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.EventLogId
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
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationGroupModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationModel
import com.terraformation.backend.plantingmanagement.PlantingSeasonNotificationType
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesUpdatedEventValues
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonRelatedPersistentEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetUpdatedEventValues
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonUpdatedEventValues
import java.time.LocalDate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

  private val plantingSiteName = "Test Site"
  private val plantingSeasonName = "Test Season"
  private val speciesName1 = "Species A"
  private val speciesName2 = "Species B"

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
    plantingSiteId = insertPlantingSite(name = plantingSiteName)
    stratumId1 = insertStratum()
    substratumId1 = insertSubstratum()
    plantingSeasonId = insertPlantingSeason(name = plantingSeasonName)
    speciesId1 = insertSpecies(scientificName = speciesName1)
    speciesId2 = insertSpecies(scientificName = speciesName2)
  }

  private fun model(
      lastEventLogId: EventLogId,
      events: List<PlantingSeasonNotificationModel>,
      plantingSeasonId: PlantingSeasonId = this.plantingSeasonId,
      plantingSeasonName: String = this.plantingSeasonName,
      plantingSiteName: String = this.plantingSiteName,
  ) =
      PlantingSeasonNotificationGroupModel(
          plantingSeasonId = plantingSeasonId,
          plantingSeasonName = plantingSeasonName,
          plantingSiteName = plantingSiteName,
          lastEventLogId = lastEventLogId,
          notifications = events,
      )

  private fun targetAdded(vararg speciesNames: String) =
      PlantingSeasonNotificationModel(
          PlantingSeasonNotificationType.SpeciesTargetsAdded,
          speciesNames.toSet(),
      )

  private fun targetUpdated(vararg speciesNames: String) =
      PlantingSeasonNotificationModel(
          PlantingSeasonNotificationType.SpeciesTargetsUpdated,
          speciesNames.toSet(),
      )

  private val pastEndDate =
      PlantingSeasonNotificationModel(PlantingSeasonNotificationType.PlantingSeasonPastEndDate)

  private val closed =
      PlantingSeasonNotificationModel(PlantingSeasonNotificationType.PlantingSeasonClosed)

  @Nested
  inner class GetInventoryPlanningNotificationsByPlantingSeason {
    @Test
    fun `returns all notifications when the season has never dismissed a notification`() {
      insertSpeciesTargetCreatedEvent(speciesId = speciesId1)
      clock.instant = clock.instant.plusSeconds(1)
      val updatedEvent = insertSpeciesTargetUpdatedEvent(speciesId = speciesId1)

      assertEquals(
          model(updatedEvent.id, listOf(targetAdded(speciesName1), targetUpdated(speciesName1))),
          service.getInventoryPlanningNotifications(plantingSeasonId),
      )
    }

    @Test
    fun `returns only notifications for events logged after the dismissal watermark`() {
      val dismissed = insertSpeciesTargetCreatedEvent(speciesId = speciesId1)
      clock.instant = clock.instant.plusSeconds(1)
      val newer = insertSpeciesTargetUpdatedEvent(speciesId = speciesId1)
      insertPlantingSeasonNotification(lastDismissedEventLogId = dismissed.id)

      assertEquals(
          model(newer.id, listOf(targetUpdated(speciesName1))),
          service.getInventoryPlanningNotifications(plantingSeasonId),
      )
    }

    @Test
    fun `excludes events for other planting seasons`() {
      val otherSeasonId = insertPlantingSeason(name = "Other Season")
      val expected = insertSpeciesTargetCreatedEvent(speciesId = speciesId1)
      insertSpeciesTargetCreatedEvent(speciesId = speciesId1, plantingSeasonId = otherSeasonId)

      assertEquals(
          model(expected.id, listOf(targetAdded(speciesName1))),
          service.getInventoryPlanningNotifications(plantingSeasonId),
      )
    }

    @Test
    fun `ignores events that do not map to a notification type`() {
      insertCreatedEvent()
      clock.instant = clock.instant.plusSeconds(1)
      val target = insertSpeciesTargetCreatedEvent(speciesId = speciesId1)

      assertEquals(
          model(target.id, listOf(targetAdded(speciesName1))),
          service.getInventoryPlanningNotifications(plantingSeasonId),
      )
    }

    @Test
    fun `returns null when there are no undismissed events`() {
      val dismissed = insertSpeciesTargetCreatedEvent(speciesId = speciesId1)
      insertPlantingSeasonNotification(lastDismissedEventLogId = dismissed.id)

      assertNull(service.getInventoryPlanningNotifications(plantingSeasonId))
    }

    @Test
    fun `throws exception when user has no permission to read the planting season`() {
      deleteOrganizationUser()

      assertThrows<PlantingSeasonNotFoundException> {
        service.getInventoryPlanningNotifications(plantingSeasonId)
      }
    }
  }

  @Nested
  inner class GetPlantingSeasonPlanningNotificationsByPlantingSeason {
    @Test
    fun `returns all notifications when the season has never dismissed a notification`() {
      insertSpeciesAllocatedCreatedEvent(speciesId = speciesId1)
      clock.instant = clock.instant.plusSeconds(1)
      val updatedEvent = insertSpeciesAllocatedCreatedEvent(speciesId = speciesId2, quantity = 2)

      assertEquals(
          model(
              updatedEvent.id,
              listOf(
                  PlantingSeasonNotificationModel(
                      PlantingSeasonNotificationType.AllocationQuantitiesUpdated
                  )
              ),
          ),
          service.getPlantingSeasonPlanningNotifications(plantingSeasonId),
      )
    }

    @Test
    fun `returns only notifications for events logged after the dismissal watermark`() {
      val dismissed = insertSpeciesAllocatedCreatedEvent(speciesId = speciesId1)
      clock.instant = clock.instant.plusSeconds(1)
      val newer = insertSpeciesAllocatedCreatedEvent(speciesId = speciesId2, quantity = 2)
      insertPlantingSeasonNotification(lastDismissedEventLogId = dismissed.id)

      assertEquals(
          model(
              newer.id,
              listOf(
                  PlantingSeasonNotificationModel(
                      PlantingSeasonNotificationType.AllocationQuantitiesUpdated
                  )
              ),
          ),
          service.getPlantingSeasonPlanningNotifications(plantingSeasonId),
      )
    }

    @Test
    fun `excludes events for other planting seasons`() {
      val otherSeasonId = insertPlantingSeason(name = "Other Season")
      val expected = insertSpeciesAllocatedCreatedEvent(speciesId = speciesId1)
      insertSpeciesAllocatedCreatedEvent(speciesId = speciesId1, plantingSeasonId = otherSeasonId)

      assertEquals(
          model(
              expected.id,
              listOf(
                  PlantingSeasonNotificationModel(
                      PlantingSeasonNotificationType.AllocationQuantitiesUpdated
                  )
              ),
          ),
          service.getPlantingSeasonPlanningNotifications(plantingSeasonId),
      )
    }

    @Test
    fun `ignores events that do not map to a notification type`() {
      insertCreatedEvent() // should be ignored
      clock.instant = clock.instant.plusSeconds(1)
      val target = insertSpeciesAllocatedCreatedEvent(speciesId = speciesId1)

      assertEquals(
          model(
              target.id,
              listOf(
                  PlantingSeasonNotificationModel(
                      PlantingSeasonNotificationType.AllocationQuantitiesUpdated
                  )
              ),
          ),
          service.getPlantingSeasonPlanningNotifications(plantingSeasonId),
      )
    }

    @Test
    fun `combines events of the same type`() {
      insertSpeciesAllocatedCreatedEvent(speciesId = speciesId1)
      clock.instant = clock.instant.plusSeconds(1)
      val latest = insertSpeciesAllocatedUpdatedEvent(speciesId = speciesId1)

      assertEquals(
          model(
              latest.id,
              listOf(
                  PlantingSeasonNotificationModel(
                      PlantingSeasonNotificationType.AllocationQuantitiesUpdated
                  )
              ),
          ),
          service.getPlantingSeasonPlanningNotifications(plantingSeasonId),
      )
    }

    @Test
    fun `returns null when there are no undismissed events`() {
      val dismissed = insertSpeciesAllocatedCreatedEvent(speciesId = speciesId1)
      insertPlantingSeasonNotification(lastDismissedEventLogId = dismissed.id)

      assertNull(service.getPlantingSeasonPlanningNotifications(plantingSeasonId))
    }

    @Test
    fun `throws exception when user has no permission to read the planting season`() {
      deleteOrganizationUser()

      assertThrows<PlantingSeasonNotFoundException> {
        service.getPlantingSeasonPlanningNotifications(plantingSeasonId)
      }
    }
  }

  @Nested
  inner class GetInventoryPlanningNotificationsByOrganization {
    @Test
    fun `groups notifications by planting season`() {
      val otherSeasonId = insertPlantingSeason(name = "Other Season")
      val firstSeasonEvent = insertSpeciesTargetCreatedEvent(speciesId = speciesId1)
      val otherSeasonEvent =
          insertSpeciesTargetCreatedEvent(speciesId = speciesId1, plantingSeasonId = otherSeasonId)

      assertEquals(
          mapOf(
              plantingSeasonId to model(firstSeasonEvent.id, listOf(targetAdded(speciesName1))),
              otherSeasonId to
                  model(
                      otherSeasonEvent.id,
                      listOf(targetAdded(speciesName1)),
                      plantingSeasonId = otherSeasonId,
                      plantingSeasonName = "Other Season",
                  ),
          ),
          service.getInventoryPlanningNotifications(organizationId).associateBy {
            it.plantingSeasonId
          },
      )
    }

    @Test
    fun `applies each season's dismissal watermark independently`() {
      val otherSeasonId = insertPlantingSeason(name = "Other Season")
      val dismissed = insertSpeciesTargetCreatedEvent(speciesId = speciesId1)
      clock.instant = clock.instant.plusSeconds(1)
      val firstSeasonNewer = insertSpeciesTargetUpdatedEvent(speciesId = speciesId1)
      val otherSeasonEvent =
          insertSpeciesTargetCreatedEvent(speciesId = speciesId1, plantingSeasonId = otherSeasonId)
      insertPlantingSeasonNotification(
          plantingSeasonId = plantingSeasonId,
          lastDismissedEventLogId = dismissed.id,
      )

      assertEquals(
          mapOf(
              plantingSeasonId to model(firstSeasonNewer.id, listOf(targetUpdated(speciesName1))),
              otherSeasonId to
                  model(
                      otherSeasonEvent.id,
                      listOf(targetAdded(speciesName1)),
                      plantingSeasonId = otherSeasonId,
                      plantingSeasonName = "Other Season",
                  ),
          ),
          service.getInventoryPlanningNotifications(organizationId).associateBy {
            it.plantingSeasonId
          },
      )
    }

    @Test
    fun `excludes events from other organizations`() {
      val expected = insertSpeciesTargetCreatedEvent(speciesId = speciesId1)

      val otherOrganizationId = insertOrganization()
      val otherSiteId = insertPlantingSite(organizationId = otherOrganizationId)
      val otherSeasonId = insertPlantingSeason(plantingSiteId = otherSiteId)
      insertSpeciesTargetCreatedEvent(
          speciesId = speciesId1,
          organizationId = otherOrganizationId,
          plantingSiteId = otherSiteId,
          plantingSeasonId = otherSeasonId,
      )

      assertEquals(
          mapOf(plantingSeasonId to model(expected.id, listOf(targetAdded(speciesName1)))),
          service.getInventoryPlanningNotifications(organizationId).associateBy {
            it.plantingSeasonId
          },
      )
    }

    @Test
    fun `returns empty list when the organization has no planting seasons`() {
      val emptyOrganizationId = insertOrganization()

      assertEquals(
          emptyList<PlantingSeasonNotificationGroupModel>(),
          service.getInventoryPlanningNotifications(emptyOrganizationId),
      )
    }

    @Test
    fun `throws exception when user lacks permission for a season in the organization`() {
      deleteOrganizationUser()

      assertThrows<PlantingSeasonNotFoundException> {
        service.getInventoryPlanningNotifications(organizationId)
      }
    }
  }

  @Nested
  inner class CombineEvents {
    @Test
    fun `collapses events of the same type across species into a single notification`() {
      insertSpeciesTargetUpdatedEvent(speciesId = speciesId1)
      insertSpeciesTargetUpdatedEvent(speciesId = speciesId2)

      val notification = service.getInventoryPlanningNotifications(plantingSeasonId)

      assertEquals(
          listOf(targetUpdated(speciesName1, speciesName2)),
          notification?.notifications,
      )
    }

    @Test
    fun `keeps species even if the changes are reverted`() {
      insertSpeciesTargetUpdatedEvent(
          speciesId = speciesId1,
          changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 5),
          changedTo = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 10),
      )
      insertSpeciesTargetUpdatedEvent(speciesId = speciesId2)
      insertSpeciesTargetUpdatedEvent(
          speciesId = speciesId1,
          changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 10),
          changedTo = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 5),
      )

      val notification = service.getInventoryPlanningNotifications(plantingSeasonId)

      assertEquals(
          listOf(targetUpdated(speciesName1, speciesName2)),
          notification?.notifications,
      )
    }

    @Test
    fun `keeps a separate notification per notification type in first-seen order`() {
      insertSpeciesTargetCreatedEvent(speciesId = speciesId1)
      clock.instant = clock.instant.plusSeconds(1)
      insertSpeciesTargetUpdatedEvent(speciesId = speciesId1)
      clock.instant = clock.instant.plusSeconds(1)
      val close =
          insertUpdatedEvent(
              changedFrom = PlantingSeasonUpdatedEventValues(status = PlantingSeasonStatus.Active),
              changedTo = PlantingSeasonUpdatedEventValues(status = PlantingSeasonStatus.Closed),
          )

      assertEquals(
          model(close.id, listOf(targetAdded(speciesName1), targetUpdated(speciesName1), closed)),
          service.getInventoryPlanningNotifications(plantingSeasonId),
      )
    }

    @Test
    fun `maps status change notifications correctly`() {
      insertUpdatedEvent(
          changedFrom = PlantingSeasonUpdatedEventValues(name = "A"),
          changedTo = PlantingSeasonUpdatedEventValues(name = "B"),
      )
      clock.instant = clock.instant.plusSeconds(1)
      insertUpdatedEvent(
          changedFrom = PlantingSeasonUpdatedEventValues(status = PlantingSeasonStatus.Active),
          changedTo = PlantingSeasonUpdatedEventValues(status = PlantingSeasonStatus.PastEndDate),
      )
      val closedEvent =
          insertUpdatedEvent(
              changedFrom =
                  PlantingSeasonUpdatedEventValues(status = PlantingSeasonStatus.PastEndDate),
              changedTo = PlantingSeasonUpdatedEventValues(status = PlantingSeasonStatus.Closed),
          )

      assertEquals(
          model(closedEvent.id, listOf(pastEndDate, closed)),
          service.getInventoryPlanningNotifications(plantingSeasonId),
      )
    }
  }

  private fun insertUpdatedEvent(
      changedFrom: PlantingSeasonUpdatedEventValues = PlantingSeasonUpdatedEventValues(),
      changedTo: PlantingSeasonUpdatedEventValues = PlantingSeasonUpdatedEventValues(),
      organizationId: OrganizationId = this.organizationId,
      plantingSeasonId: PlantingSeasonId = this.plantingSeasonId,
      plantingSiteId: PlantingSiteId = this.plantingSiteId,
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
      name: String = "Season",
      organizationId: OrganizationId = this.organizationId,
      plantingSeasonId: PlantingSeasonId = this.plantingSeasonId,
      plantingSiteId: PlantingSiteId = this.plantingSiteId,
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

  private fun insertSpeciesTargetCreatedEvent(
      organizationId: OrganizationId = this.organizationId,
      plantingSeasonId: PlantingSeasonId = this.plantingSeasonId,
      plantingSiteId: PlantingSiteId = this.plantingSiteId,
      quantity: Int = 1,
      speciesId: SpeciesId = speciesId1,
      substratumId: SubstratumId = substratumId1,
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
      changedFrom: PlantingSeasonSpeciesTargetUpdatedEventValues =
          PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 5),
      changedTo: PlantingSeasonSpeciesTargetUpdatedEventValues =
          PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 7),
      organizationId: OrganizationId = this.organizationId,
      plantingSeasonId: PlantingSeasonId = this.plantingSeasonId,
      plantingSiteId: PlantingSiteId = this.plantingSiteId,
      speciesId: SpeciesId = speciesId1,
      substratumId: SubstratumId = substratumId1,
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

  private fun insertSpeciesAllocatedCreatedEvent(
      organizationId: OrganizationId = this.organizationId,
      plantingSeasonId: PlantingSeasonId = this.plantingSeasonId,
      plantingSiteId: PlantingSiteId = this.plantingSiteId,
      quantity: Int = 1,
      speciesId: SpeciesId = speciesId1,
  ): EventLogEntry<PlantingSeasonAllocatedSpeciesPersistentEvent> {
    val event =
        PlantingSeasonAllocatedSpeciesCreatedEvent(
            organizationId = organizationId,
            plantingSeasonId = plantingSeasonId,
            plantingSiteId = plantingSiteId,
            quantity = quantity,
            speciesId = speciesId,
        )

    return EventLogEntry(user.userId, clock.instant, event, eventLogStore.insertEvent(event))
  }

  private fun insertSpeciesAllocatedUpdatedEvent(
      changedFrom: PlantingSeasonAllocatedSpeciesUpdatedEventValues =
          PlantingSeasonAllocatedSpeciesUpdatedEventValues(quantity = 5),
      changedTo: PlantingSeasonAllocatedSpeciesUpdatedEventValues =
          PlantingSeasonAllocatedSpeciesUpdatedEventValues(quantity = 7),
      organizationId: OrganizationId = this.organizationId,
      plantingSeasonId: PlantingSeasonId = this.plantingSeasonId,
      plantingSiteId: PlantingSiteId = this.plantingSiteId,
      speciesId: SpeciesId = speciesId1,
  ): EventLogEntry<PlantingSeasonAllocatedSpeciesPersistentEvent> {
    val event =
        PlantingSeasonAllocatedSpeciesUpdatedEvent(
            changedFrom = changedFrom,
            changedTo = changedTo,
            organizationId = organizationId,
            plantingSeasonId = plantingSeasonId,
            plantingSiteId = plantingSiteId,
            speciesId = speciesId,
        )

    return EventLogEntry(user.userId, clock.instant, event, eventLogStore.insertEvent(event))
  }
}
