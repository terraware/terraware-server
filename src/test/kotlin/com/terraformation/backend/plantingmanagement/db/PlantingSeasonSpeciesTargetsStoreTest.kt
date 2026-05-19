package com.terraformation.backend.plantingmanagement.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.records.PlantingSeasonSpeciesTargetsRecord
import com.terraformation.backend.plantingmanagement.PlantingSeasonSpeciesTargetModel
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
  private val store: PlantingSeasonSpeciesTargetsStore by lazy {
    PlantingSeasonSpeciesTargetsStore(clock, dslContext)
  }

  private lateinit var plantingSeasonId: PlantingSeasonId
  private lateinit var substratumId: SubstratumId
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertPlantingSite()
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
      store.upsert(plantingSeasonId, substratumId, speciesId, quantity = 5)
      store.upsert(plantingSeasonId, substratumId, speciesId2, quantity = 10)

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
      store.upsert(plantingSeasonId, substratumId, speciesId, quantity = 5)
      store.upsert(otherPlantingSeasonId, substratumId, speciesId, quantity = 10)

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
    }

    @Test
    fun `updates quantity when row already exists`() {
      store.upsert(plantingSeasonId, substratumId, speciesId, quantity = 5)
      clock.instant = Instant.ofEpochSecond(100)
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
}
