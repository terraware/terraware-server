package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSeasonStatus
import com.terraformation.backend.db.tracking.tables.records.PlantingSeasonAllocatedSpeciesRecord
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_ALLOCATED_SPECIES
import com.terraformation.backend.plantingmanagement.event.PlantingSeasonSpeciesTargetDeletedEvent
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSeasonAllocatedSpeciesStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val store: PlantingSeasonAllocatedSpeciesStore by lazy {
    PlantingSeasonAllocatedSpeciesStore(clock, dslContext, SeasonHelper(dslContext))
  }

  private lateinit var plantingSeasonId: PlantingSeasonId
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(role = Role.Manager)
    insertPlantingSite()
    plantingSeasonId = insertPlantingSeason()
    speciesId = insertSpecies()
  }

  @Nested
  inner class Upsert {
    @Test
    fun `inserts a new species allocation row`() {
      store.upsert(plantingSeasonId, speciesId, 5)

      assertTableEquals(
          PlantingSeasonAllocatedSpeciesRecord(
              plantingSeasonId = plantingSeasonId,
              speciesId = speciesId,
              quantity = 5,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          ),
      )
    }

    @Test
    fun `updates quantity when row already exists`() {
      store.upsert(plantingSeasonId, speciesId, quantity = 5)
      clock.instant = Instant.ofEpochSecond(100)
      store.upsert(plantingSeasonId, speciesId, quantity = 20)

      assertTableEquals(
          PlantingSeasonAllocatedSpeciesRecord(
              plantingSeasonId = plantingSeasonId,
              speciesId = speciesId,
              quantity = 20,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )
      )
    }

    @Test
    fun `throws PlantingSeasonClosedException if season is closed`() {
      val plantingSeasonId = insertPlantingSeason(status = PlantingSeasonStatus.Closed)

      assertThrows<PlantingSeasonClosedException> {
        store.upsert(plantingSeasonId, speciesId, quantity = 5)
      }
    }

    @Test
    fun `throws AccessDeniedException when user lacks permission`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.upsert(plantingSeasonId, speciesId, quantity = 5)
      }
    }
  }

  @Nested
  inner class SpeciesTargetDeletedEvent {
    @Test
    fun `doesn't delete allocated species when target exists`() {
      insertStratum()
      val substratumId1 = insertSubstratum()
      insertPlantingSeasonSpeciesTarget(substratumId = substratumId1, quantity = 5)
      insertPlantingSeasonAllocatedSpecies(speciesId = speciesId, quantity = 15)

      store.on(
          PlantingSeasonSpeciesTargetDeletedEvent(
              plantingSeasonId = plantingSeasonId,
              speciesId = speciesId,
              substratumId = substratumId1,
          )
      )

      assertTableEquals(
          PlantingSeasonAllocatedSpeciesRecord(
              plantingSeasonId = plantingSeasonId,
              speciesId = speciesId,
              quantity = 15,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          ),
      )
    }

    @Test
    fun `deletes allocated species when no more targets exist`() {
      insertStratum()
      val substratumId1 = insertSubstratum()
      insertPlantingSeasonAllocatedSpecies(speciesId = speciesId, quantity = 15)

      store.on(
          PlantingSeasonSpeciesTargetDeletedEvent(
              plantingSeasonId = plantingSeasonId,
              speciesId = speciesId,
              substratumId = substratumId1,
          )
      )

      assertTableEmpty(PLANTING_SEASON_ALLOCATED_SPECIES)
    }
  }
}
