package com.terraformation.backend.plantingmanagement

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.tracking.PlantingSeasonId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.SubstratumId
import com.terraformation.backend.db.tracking.tables.records.PlantingSeasonSpeciesTargetsRecord
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SEASON_SPECIES_TARGETS
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonSpeciesTargetsStore
import com.terraformation.backend.plantingmanagement.db.PlantingSeasonStore
import java.time.Instant
import java.time.LocalDate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class PlantingSeasonServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val parentStore = ParentStore(dslContext)
  private val plantingSeasonSpeciesTargetsStore: PlantingSeasonSpeciesTargetsStore by lazy {
    PlantingSeasonSpeciesTargetsStore(clock, dslContext, eventPublisher, parentStore)
  }
  private val plantingSeasonStore: PlantingSeasonStore by lazy {
    PlantingSeasonStore(clock, dslContext, eventPublisher, parentStore)
  }
  private val service: PlantingSeasonService by lazy {
    PlantingSeasonService(dslContext, plantingSeasonStore, plantingSeasonSpeciesTargetsStore)
  }

  private lateinit var plantingSeasonId: PlantingSeasonId
  private lateinit var plantingSiteId: PlantingSiteId
  private lateinit var substratumId: SubstratumId
  private lateinit var speciesId: SpeciesId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    plantingSiteId = insertPlantingSite()
    insertOrganizationUser(role = Role.Manager)
    insertStratum()
    plantingSeasonId = insertPlantingSeason()
    substratumId = insertSubstratum()
    speciesId = insertSpecies()
  }

  @Nested
  inner class Create {
    private val startDate = LocalDate.of(2025, 1, 1)
    private val endDate = LocalDate.of(2025, 3, 31)

    @Test
    fun `copies species targets from source season when fromPlantingSeasonId is specified`() {
      insertPlantingSeasonSpeciesTarget(speciesId = speciesId, quantity = 5)

      val newSeasonId =
          service.create(
              NewPlantingSeasonModel(
                  endDate = endDate,
                  fromPlantingSeasonId = plantingSeasonId,
                  name = "Spring 2025",
                  plantingSiteId = plantingSiteId,
                  startDate = startDate,
              )
          )

      assertTableEquals(
          listOf(
              PlantingSeasonSpeciesTargetsRecord(
                  plantingSeasonId = plantingSeasonId,
                  substratumId = substratumId,
                  speciesId = speciesId,
                  quantity = 5,
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
              ),
              PlantingSeasonSpeciesTargetsRecord(
                  plantingSeasonId = newSeasonId,
                  substratumId = substratumId,
                  speciesId = speciesId,
                  quantity = 0,
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
              ),
          )
      )
    }

    @Test
    fun `does not copy species targets when fromPlantingSeasonId is null`() {
      insertPlantingSeason()
      insertPlantingSeasonSpeciesTarget(speciesId = speciesId, quantity = 5)

      val newSeasonId =
          service.create(
              NewPlantingSeasonModel(
                  endDate = endDate,
                  name = "Spring 2025",
                  plantingSiteId = plantingSiteId,
                  startDate = startDate,
              )
          )

      assertTableEmpty(
          PLANTING_SEASON_SPECIES_TARGETS,
          where = PLANTING_SEASON_SPECIES_TARGETS.PLANTING_SEASON_ID.eq(newSeasonId),
      )
    }
  }
}
