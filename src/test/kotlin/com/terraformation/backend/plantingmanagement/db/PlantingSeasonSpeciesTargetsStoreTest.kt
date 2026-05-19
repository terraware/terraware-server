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
import java.time.Instant
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
