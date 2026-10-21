package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.records.PlantingSeasonAllocatedSpeciesRecord
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_ALLOCATED_SPECIES
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesDeletedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonAllocatedSpeciesUpdatedEventValues
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetDeletedEvent
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSeasonAllocatedSpeciesStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val eventPublisher = TestEventPublisher()
  private val store: PlantingSeasonAllocatedSpeciesStore by lazy {
    PlantingSeasonAllocatedSpeciesStore(clock, dslContext, eventPublisher, SeasonHelper(dslContext))
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(role = Role.Manager)
    insertPlantingSite()
    insertPlantingSeason()
    insertSpecies()
  }

  @Nested
  inner class Upsert {
    @Test
    fun `inserts a new species allocation row and publishes event`() {
      store.upsert(inserted.plantingSeasonId, inserted.speciesId, 5)

      assertTableEquals(
          PlantingSeasonAllocatedSpeciesRecord(
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              plantingSeasonId = inserted.plantingSeasonId,
              quantity = 5,
              speciesId = inserted.speciesId,
          ),
      )

      eventPublisher.assertEventPublished(
          PlantingSeasonAllocatedSpeciesCreatedEvent(
              organizationId = inserted.organizationId,
              plantingSeasonId = inserted.plantingSeasonId,
              plantingSiteId = inserted.plantingSiteId,
              quantity = 5,
              speciesId = inserted.speciesId,
          )
      )
    }

    @Test
    fun `updates quantity and publishes event when row already exists`() {
      store.upsert(inserted.plantingSeasonId, inserted.speciesId, quantity = 5)
      clock.instant = Instant.ofEpochSecond(100)
      store.upsert(inserted.plantingSeasonId, inserted.speciesId, quantity = 20)

      assertTableEquals(
          PlantingSeasonAllocatedSpeciesRecord(
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              plantingSeasonId = inserted.plantingSeasonId,
              quantity = 20,
              speciesId = inserted.speciesId,
          )
      )

      eventPublisher.assertEventPublished(
          PlantingSeasonAllocatedSpeciesUpdatedEvent(
              changedFrom = PlantingSeasonAllocatedSpeciesUpdatedEventValues(quantity = 5),
              changedTo = PlantingSeasonAllocatedSpeciesUpdatedEventValues(quantity = 20),
              organizationId = inserted.organizationId,
              plantingSeasonId = inserted.plantingSeasonId,
              plantingSiteId = inserted.plantingSiteId,
              speciesId = inserted.speciesId,
          )
      )
    }

    @Test
    fun `does not publish an event when the quantity is unchanged`() {
      store.upsert(inserted.plantingSeasonId, inserted.speciesId, quantity = 5)
      eventPublisher.clear()
      store.upsert(inserted.plantingSeasonId, inserted.speciesId, quantity = 5)

      eventPublisher.assertNoEventsPublished()
    }

    @Test
    fun `throws PlantingSeasonClosedException if season is closed`() {
      val closedSeasonId = insertPlantingSeason(status = PlantingSeasonStatus.Closed)

      assertThrows<PlantingSeasonClosedException> {
        store.upsert(closedSeasonId, inserted.speciesId, quantity = 5)
      }
    }

    @Test
    fun `throws AccessDeniedException when user lacks permission`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.upsert(inserted.plantingSeasonId, inserted.speciesId, quantity = 5)
      }

      assertTableEmpty(PLANTING_SEASON_ALLOCATED_SPECIES)
    }
  }

  @Nested
  inner class SpeciesTargetDeletedEvent {
    private fun speciesTargetDeletedEvent(substratumId: SubstratumId) =
        PlantingSeasonSpeciesTargetDeletedEvent(
            organizationId = inserted.organizationId,
            plantingSeasonId = inserted.plantingSeasonId,
            plantingSiteId = inserted.plantingSiteId,
            speciesId = inserted.speciesId,
            stratumName = "S1",
            substratumHistoryId = inserted.substratumHistoryId,
            substratumId = substratumId,
            substratumName = "1",
        )

    @Test
    fun `doesn't delete allocated species when target exists`() {
      insertStratum()
      val substratumId = insertSubstratum()
      insertPlantingSeasonSpeciesTarget(quantity = 5)
      insertPlantingSeasonAllocatedSpecies(quantity = 15)

      store.on(speciesTargetDeletedEvent(substratumId))

      assertTableEquals(
          PlantingSeasonAllocatedSpeciesRecord(
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              plantingSeasonId = inserted.plantingSeasonId,
              quantity = 15,
              speciesId = inserted.speciesId,
          ),
      )
    }

    @Test
    fun `deletes allocated species when no more targets exist`() {
      insertStratum()
      val substratumId = insertSubstratum()
      insertPlantingSeasonAllocatedSpecies(quantity = 15)

      store.on(speciesTargetDeletedEvent(substratumId))

      assertTableEmpty(PLANTING_SEASON_ALLOCATED_SPECIES)

      eventPublisher.assertEventPublished(
          PlantingSeasonAllocatedSpeciesDeletedEvent(
              organizationId = inserted.organizationId,
              plantingSeasonId = inserted.plantingSeasonId,
              plantingSiteId = inserted.plantingSiteId,
              speciesId = inserted.speciesId,
          )
      )
    }

    @Test
    fun `does not publish a deleted event when the allocation is retained`() {
      insertStratum()
      val substratumId = insertSubstratum()
      insertPlantingSeasonSpeciesTarget(quantity = 5)
      insertPlantingSeasonAllocatedSpecies(quantity = 15)

      store.on(speciesTargetDeletedEvent(substratumId))

      eventPublisher.assertEventNotPublished<PlantingSeasonAllocatedSpeciesDeletedEvent>()
    }
  }
}
