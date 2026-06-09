package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.records.PlantingSeasonSpeciesTargetsRecord
import com.terraformation.backend.plantingmanagement.PlantingSeasonSpeciesTargetModel
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetCreatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetDeletedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetUpdatedEvent
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetUpdatedEventValues
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSeasonSpeciesTargetsStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store: PlantingSeasonSpeciesTargetsStore by lazy {
    PlantingSeasonSpeciesTargetsStore(
        clock,
        dslContext,
        eventPublisher,
        SeasonHelper(dslContext),
    )
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var plantingSeasonId: PlantingSeasonId
  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var substratumId: SubstratumId
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    plantingSiteId = insertPlantingSite(x = 0)
    insertOrganizationUser(role = Role.Manager)
    insertStratum()
    plantingSeasonId = insertPlantingSeason()
    substratumId = insertSubstratum()
    speciesId = insertSpecies()
  }

  @Nested
  inner class FetchList {
    @Test
    fun `returns empty list when no targets exist`() {
      val result = store.fetchList(plantingSeasonId)

      assertEquals(emptyList<PlantingSeasonSpeciesTargetModel>(), result)
    }

    @Test
    fun `returns all targets for the planting season`() {
      val speciesId2 = insertSpecies()
      insertPlantingSeasonSpeciesTarget(speciesId = speciesId, quantity = 5)
      insertPlantingSeasonSpeciesTarget(speciesId = speciesId2, quantity = 10)

      val result = store.fetchList(plantingSeasonId)

      assertEquals(
          setOf(
              PlantingSeasonSpeciesTargetModel(
                  substratumId = substratumId,
                  speciesId = speciesId,
                  quantity = 5,
              ),
              PlantingSeasonSpeciesTargetModel(
                  substratumId = substratumId,
                  speciesId = speciesId2,
                  quantity = 10,
              ),
          ),
          result.toSet(),
      )
    }

    @Test
    fun `only returns targets for the specified planting season`() {
      val otherPlantingSeasonId = insertPlantingSeason()
      insertPlantingSeasonSpeciesTarget(
          plantingSeasonId = plantingSeasonId,
          speciesId = speciesId,
          quantity = 5,
      )
      insertPlantingSeasonSpeciesTarget(
          plantingSeasonId = otherPlantingSeasonId,
          speciesId = speciesId,
          quantity = 10,
      )

      val result = store.fetchList(plantingSeasonId)

      assertEquals(
          listOf(
              PlantingSeasonSpeciesTargetModel(
                  substratumId = substratumId,
                  speciesId = speciesId,
                  quantity = 5,
              )
          ),
          result,
      )
    }

    @Test
    fun `throws PlantingSeasonNotFoundException when user is not a member of the organization`() {
      deleteOrganizationUser()

      assertThrows<PlantingSeasonNotFoundException> { store.fetchList(plantingSeasonId) }
    }
  }

  @Nested
  inner class Upsert {
    @Test
    fun `inserts a new species target row`() {
      store.upsert(plantingSeasonId, substratumId, speciesId, quantity = 5)

      assertTableEquals(
          PlantingSeasonSpeciesTargetsRecord(
              plantingSeasonId = plantingSeasonId,
              substratumId = substratumId,
              speciesId = speciesId,
              quantity = 5,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )

      eventPublisher.assertEventPublished(
          PlantingSeasonSpeciesTargetCreatedEvent(
              organizationId = organizationId,
              plantingSeasonId = plantingSeasonId,
              plantingSiteId = plantingSiteId,
              quantity = 5,
              speciesId = speciesId,
              stratumName = "S1",
              substratumHistoryId = inserted.substratumHistoryId,
              substratumId = substratumId,
              substratumName = "1",
          )
      )
    }

    @Test
    fun `updates quantity when row already exists`() {
      store.upsert(plantingSeasonId, substratumId, speciesId, quantity = 5)
      clock.instant = Instant.ofEpochSecond(100)
      eventPublisher.clear()
      store.upsert(plantingSeasonId, substratumId, speciesId, quantity = 20)

      assertTableEquals(
          PlantingSeasonSpeciesTargetsRecord(
              plantingSeasonId = plantingSeasonId,
              substratumId = substratumId,
              speciesId = speciesId,
              quantity = 20,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )

      eventPublisher.assertEventPublished(
          PlantingSeasonSpeciesTargetUpdatedEvent(
              changedFrom = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 5),
              changedTo = PlantingSeasonSpeciesTargetUpdatedEventValues(quantity = 20),
              organizationId = organizationId,
              plantingSeasonId = plantingSeasonId,
              plantingSiteId = plantingSiteId,
              speciesId = speciesId,
              stratumName = "S1",
              substratumHistoryId = inserted.substratumHistoryId,
              substratumId = substratumId,
              substratumName = "1",
          )
      )
    }

    @Test
    fun `throws PlantingSeasonClosedException if season is closed`() {
      val plantingSeasonId = insertPlantingSeason(status = PlantingSeasonStatus.Closed)

      assertThrows<PlantingSeasonClosedException> {
        store.upsert(plantingSeasonId, substratumId, speciesId, quantity = 5)
      }
    }

    @Test
    fun `throws AccessDeniedException when user lacks permission`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.upsert(plantingSeasonId, substratumId, speciesId, quantity = 5)
      }
    }
  }

  @Nested
  inner class CopySpeciesTargets {
    @Test
    fun `throws AccessDeniedException when user lacks permission`() {
      val otherPlantingSeasonId = insertPlantingSeason()

      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.copySpeciesTargets(otherPlantingSeasonId, plantingSeasonId)
      }
    }

    @Test
    fun `copies all species targets from another planting season`() {
      val otherPlantingSeasonId = insertPlantingSeason()
      val speciesId2 = insertSpecies()
      val initialTime = Instant.EPOCH
      clock.instant = initialTime
      insertPlantingSeasonSpeciesTarget(speciesId = speciesId, quantity = 5)
      insertPlantingSeasonSpeciesTarget(speciesId = speciesId2, quantity = 10)

      val laterTime = initialTime.plusSeconds(86400)
      clock.instant = laterTime
      store.copySpeciesTargets(otherPlantingSeasonId, plantingSeasonId)

      assertTableEquals(
          listOf(
              PlantingSeasonSpeciesTargetsRecord(
                  plantingSeasonId = otherPlantingSeasonId,
                  substratumId = substratumId,
                  speciesId = speciesId,
                  quantity = 5,
                  createdBy = user.userId,
                  createdTime = initialTime,
                  modifiedBy = user.userId,
                  modifiedTime = initialTime,
              ),
              PlantingSeasonSpeciesTargetsRecord(
                  plantingSeasonId = otherPlantingSeasonId,
                  substratumId = substratumId,
                  speciesId = speciesId2,
                  quantity = 10,
                  createdBy = user.userId,
                  createdTime = initialTime,
                  modifiedBy = user.userId,
                  modifiedTime = initialTime,
              ),
              PlantingSeasonSpeciesTargetsRecord(
                  plantingSeasonId = plantingSeasonId,
                  substratumId = substratumId,
                  speciesId = speciesId,
                  quantity = 0,
                  createdBy = user.userId,
                  createdTime = laterTime,
                  modifiedBy = user.userId,
                  modifiedTime = laterTime,
              ),
              PlantingSeasonSpeciesTargetsRecord(
                  plantingSeasonId = plantingSeasonId,
                  substratumId = substratumId,
                  speciesId = speciesId2,
                  quantity = 0,
                  createdBy = user.userId,
                  createdTime = laterTime,
                  modifiedBy = user.userId,
                  modifiedTime = laterTime,
              ),
          )
      )

      val createdEvent =
          PlantingSeasonSpeciesTargetCreatedEvent(
              organizationId = organizationId,
              plantingSeasonId = plantingSeasonId,
              plantingSiteId = plantingSiteId,
              quantity = 0,
              speciesId = speciesId,
              stratumName = "S1",
              substratumHistoryId = inserted.substratumHistoryId,
              substratumId = substratumId,
              substratumName = "1",
          )

      eventPublisher.assertEventsPublished(
          setOf(
              createdEvent,
              createdEvent.copy(speciesId = speciesId2),
          )
      )
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `deletes the species target row`() {
      insertPlantingSeasonSpeciesTarget(quantity = 5)
      val speciesId2 = insertSpecies()
      insertPlantingSeasonSpeciesTarget(speciesId = speciesId2, quantity = 10)
      store.delete(plantingSeasonId, substratumId, speciesId)

      assertTableEquals(
          PlantingSeasonSpeciesTargetsRecord(
              plantingSeasonId = plantingSeasonId,
              substratumId = substratumId,
              speciesId = speciesId2,
              quantity = 10,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )

      eventPublisher.assertEventPublished(
          PlantingSeasonSpeciesTargetDeletedEvent(
              organizationId = inserted.organizationId,
              plantingSeasonId = plantingSeasonId,
              plantingSiteId = inserted.plantingSiteId,
              speciesId = speciesId,
              stratumName = "S1",
              substratumHistoryId = inserted.substratumHistoryId,
              substratumId = substratumId,
              substratumName = "1",
          )
      )
    }

    @Test
    fun `does nothing when the species target row does not exist`() {
      store.delete(plantingSeasonId, substratumId, speciesId)
    }

    @Test
    fun `throws PlantingSeasonClosedException if season is closed`() {
      val plantingSeasonId = insertPlantingSeason(status = PlantingSeasonStatus.Closed)
      insertPlantingSeasonSpeciesTarget(quantity = 5)

      assertThrows<PlantingSeasonClosedException> {
        store.delete(plantingSeasonId, substratumId, speciesId)
      }
    }

    @Test
    fun `throws AccessDeniedException when user lacks permission`() {
      insertPlantingSeasonSpeciesTarget(quantity = 5)

      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.delete(plantingSeasonId, substratumId, speciesId)
      }
    }
  }
}
