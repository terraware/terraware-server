package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.EntityLocker
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.StratumId
import com.terraformation.backend.db.tracking.tables.records.PlantingSiteSpeciesTargetsRecord
import com.terraformation.backend.db.tracking.tables.records.StratumSpeciesTargetsRecord
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_SPECIES_TARGETS
import com.terraformation.backend.db.tracking.tables.references.STRATUM_SPECIES_TARGETS
import com.terraformation.backend.tracking.model.PlantingSiteSpeciesTargetModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteSpeciesTargetStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val store: PlantingSiteSpeciesTargetStore by lazy {
    PlantingSiteSpeciesTargetStore(
        dslContext,
        EntityLocker(dslContext),
        ParentStore(dslContext),
    )
  }

  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var stratumId1: StratumId
  private lateinit var stratumId2: StratumId
  private lateinit var speciesId1: SpeciesId
  private lateinit var speciesId2: SpeciesId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(role = Role.Admin)
    plantingSiteId = insertPlantingSite()
    stratumId1 = insertStratum(name = "Stratum 1")
    stratumId2 = insertStratum(name = "Stratum 2")
    speciesId1 = insertSpecies()
    speciesId2 = insertSpecies()
  }

  @Nested
  inner class FetchByPlantingSiteId {
    @Test
    fun `returns empty list if site has no targets`() {
      assertEquals(
          emptyList<PlantingSiteSpeciesTargetModel>(),
          store.fetchByPlantingSiteId(plantingSiteId),
      )
    }

    @Test
    fun `returns targets and their strata`() {
      insertPlantingSiteSpeciesTarget(speciesId = speciesId1, targetPlants = 100)
      insertStratumSpeciesTarget(speciesId = speciesId1, stratumId = stratumId1)
      insertStratumSpeciesTarget(speciesId = speciesId1, stratumId = stratumId2)
      insertPlantingSiteSpeciesTarget(speciesId = speciesId2)

      insertPlantingSite()
      insertPlantingSiteSpeciesTarget(speciesId = speciesId1, targetPlants = 999)

      assertEquals(
          listOf(
              PlantingSiteSpeciesTargetModel(
                  speciesId = speciesId1,
                  stratumIds = setOf(stratumId1, stratumId2),
                  targetPlants = 100,
              ),
              PlantingSiteSpeciesTargetModel(speciesId = speciesId2),
          ),
          store.fetchByPlantingSiteId(plantingSiteId),
      )
    }

    @Test
    fun `throws exception when user lacks permission to read planting site`() {
      deleteOrganizationUser()

      assertThrows<PlantingSiteNotFoundException> { store.fetchByPlantingSiteId(plantingSiteId) }
    }
  }

  @Nested
  inner class Upsert {
    @Test
    fun `inserts target with no strata and no target plant count`() {
      store.upsert(plantingSiteId, PlantingSiteSpeciesTargetModel(speciesId = speciesId1))

      assertTableEquals(
          PlantingSiteSpeciesTargetsRecord(
              plantingSiteId = plantingSiteId,
              speciesId = speciesId1,
          )
      )
      assertTableEmpty(STRATUM_SPECIES_TARGETS)
    }

    @Test
    fun `inserts target with strata`() {
      store.upsert(
          plantingSiteId,
          PlantingSiteSpeciesTargetModel(
              speciesId = speciesId1,
              stratumIds = setOf(stratumId1, stratumId2),
              targetPlants = 50,
          ),
      )

      assertTableEquals(
          PlantingSiteSpeciesTargetsRecord(
              plantingSiteId = plantingSiteId,
              speciesId = speciesId1,
              targetPlants = 50,
          )
      )
      assertTableEquals(
          listOf(
              StratumSpeciesTargetsRecord(
                  plantingSiteId = plantingSiteId,
                  speciesId = speciesId1,
                  stratumId = stratumId1,
              ),
              StratumSpeciesTargetsRecord(
                  plantingSiteId = plantingSiteId,
                  speciesId = speciesId1,
                  stratumId = stratumId2,
              ),
          )
      )
    }

    @Test
    fun `replaces target plant count and strata of existing target`() {
      insertPlantingSiteSpeciesTarget(speciesId = speciesId1, targetPlants = 100)
      insertStratumSpeciesTarget(speciesId = speciesId1, stratumId = stratumId1)
      insertPlantingSiteSpeciesTarget(speciesId = speciesId2, targetPlants = 200)
      insertStratumSpeciesTarget(speciesId = speciesId2, stratumId = stratumId1)

      store.upsert(
          plantingSiteId,
          PlantingSiteSpeciesTargetModel(
              speciesId = speciesId1,
              stratumIds = setOf(stratumId2),
              targetPlants = null,
          ),
      )

      assertTableEquals(
          listOf(
              PlantingSiteSpeciesTargetsRecord(
                  plantingSiteId = plantingSiteId,
                  speciesId = speciesId1,
              ),
              PlantingSiteSpeciesTargetsRecord(
                  plantingSiteId = plantingSiteId,
                  speciesId = speciesId2,
                  targetPlants = 200,
              ),
          )
      )
      assertTableEquals(
          listOf(
              StratumSpeciesTargetsRecord(
                  plantingSiteId = plantingSiteId,
                  speciesId = speciesId1,
                  stratumId = stratumId2,
              ),
              StratumSpeciesTargetsRecord(
                  plantingSiteId = plantingSiteId,
                  speciesId = speciesId2,
                  stratumId = stratumId1,
              ),
          )
      )
    }

    @Test
    fun `throws exception if target plant count is negative`() {
      assertThrows<IllegalArgumentException> {
        store.upsert(
            plantingSiteId,
            PlantingSiteSpeciesTargetModel(speciesId = speciesId1, targetPlants = -1),
        )
      }
    }

    @Test
    fun `throws exception if stratum is in a different planting site`() {
      insertPlantingSite()
      val otherStratumId = insertStratum()

      assertThrows<StratumNotFoundException> {
        store.upsert(
            plantingSiteId,
            PlantingSiteSpeciesTargetModel(
                speciesId = speciesId1,
                stratumIds = setOf(otherStratumId),
            ),
        )
      }

      assertTableEmpty(PLANTING_SITE_SPECIES_TARGETS)
    }

    @Test
    fun `throws exception if species is in a different organization`() {
      insertOrganization()
      insertOrganizationUser(role = Role.Admin)
      val otherSpeciesId = insertSpecies()

      assertThrows<SpeciesInWrongOrganizationException> {
        store.upsert(plantingSiteId, PlantingSiteSpeciesTargetModel(speciesId = otherSpeciesId))
      }
    }

    @Test
    fun `throws exception when user lacks permission to update planting site`() {
      insertOrganizationUser(role = Role.Manager)

      assertThrows<AccessDeniedException> {
        store.upsert(plantingSiteId, PlantingSiteSpeciesTargetModel(speciesId = speciesId1))
      }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `deletes target and its strata`() {
      insertPlantingSiteSpeciesTarget(speciesId = speciesId1, targetPlants = 100)
      insertStratumSpeciesTarget(speciesId = speciesId1, stratumId = stratumId1)
      insertPlantingSiteSpeciesTarget(speciesId = speciesId2, targetPlants = 200)
      insertStratumSpeciesTarget(speciesId = speciesId2, stratumId = stratumId1)

      store.delete(plantingSiteId, speciesId1)

      assertTableEquals(
          PlantingSiteSpeciesTargetsRecord(
              plantingSiteId = plantingSiteId,
              speciesId = speciesId2,
              targetPlants = 200,
          )
      )
      assertTableEquals(
          StratumSpeciesTargetsRecord(
              plantingSiteId = plantingSiteId,
              speciesId = speciesId2,
              stratumId = stratumId1,
          )
      )
    }

    @Test
    fun `does nothing if species is not targeted at site`() {
      store.delete(plantingSiteId, speciesId1)

      assertTableEmpty(PLANTING_SITE_SPECIES_TARGETS)
    }

    @Test
    fun `throws exception when user lacks permission to update planting site`() {
      insertOrganizationUser(role = Role.Manager)

      assertThrows<AccessDeniedException> { store.delete(plantingSiteId, speciesId1) }
    }
  }
}
