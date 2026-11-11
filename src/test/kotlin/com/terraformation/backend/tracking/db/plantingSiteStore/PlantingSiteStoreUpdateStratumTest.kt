package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.tables.pojos.StrataRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.tracking.event.StratumDensityUpdatedEvent
import com.terraformation.backend.tracking.event.StratumPlotCountsUpdatedEvent
import com.terraformation.backend.tracking.model.DensityChangedEventModel
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreUpdateStratumTest : BasePlantingSiteStoreTest() {

  @Nested
  inner class UpdateStratum {
    @Test
    fun `updates editable values`() {
      val createdTime = Instant.ofEpochSecond(1000)
      val createdBy = insertUser()
      val plantingSiteId = insertPlantingSite()

      val stratumId =
          insertStratum(
              areaHa = BigDecimal.ONE,
              boundary = multiPolygon(1),
              createdTime = createdTime,
              initialPlantingDensity = BigDecimal.ONE,
              name = "initial",
              plantErrorMargin = BigDecimal.TWO,
              plantNumPermanentPlots = 1,
              plantNumTemporaryPlots = 2,
              plantStudentsT = BigDecimal.ONE,
              plantVariance = BigDecimal.ZERO,
          )

      val newName = "renamed"
      val newBiomassErrorMargin = BigDecimal(5)
      val newBiomassStudentsT = BigDecimal(6)
      val newBiomassVariance = BigDecimal(7)
      val newBiomassNumPermanentPlots = 8
      val newBiomassNumTemporaryPlots = 9
      val newPlantErrorMargin = BigDecimal(10)
      val newPlantStudentsT = BigDecimal(11)
      val newPlantVariance = BigDecimal(12)
      val newPlantNumPermanentPlots = 13
      val newPlantNumTemporaryPlots = 14
      val newInitialPlantingDensity = BigDecimal(13)
      val newTargetPlantDensity = BigDecimal(15)

      val expected =
          StrataRow(
              areaHa = BigDecimal.ONE,
              baselineErrorMargin = BigDecimal(21),
              baselineNumTemporaryPlots = 45,
              baselineStudentsT = BigDecimal(24),
              baselineVariance = BigDecimal(25),
              biomassErrorMargin = newBiomassErrorMargin,
              biomassNumPermanentPlots = newBiomassNumPermanentPlots,
              biomassNumTemporaryPlots = newBiomassNumTemporaryPlots,
              biomassStudentsT = newBiomassStudentsT,
              biomassVariance = newBiomassVariance,
              boundary = multiPolygon(1),
              boundaryModifiedBy = createdBy,
              boundaryModifiedTime = createdTime,
              createdBy = createdBy,
              createdTime = createdTime,
              id = stratumId,
              initialPlantingDensity = newInitialPlantingDensity,
              modifiedBy = user.userId,
              modifiedTime = clock.instant(),
              name = newName,
              plantErrorMargin = newPlantErrorMargin,
              plantNumPermanentPlots = newPlantNumPermanentPlots,
              plantNumTemporaryPlots = newPlantNumTemporaryPlots,
              plantStudentsT = newPlantStudentsT,
              plantVariance = newPlantVariance,
              plantingSiteId = plantingSiteId,
              stableId = StableId("initial"),
              targetPlantDensity = newTargetPlantDensity,
          )

      store.updateStratum(stratumId) {
        it.copy(
            // Editable
            baselineErrorMargin = BigDecimal(21),
            baselineNumTemporaryPlots = 45,
            baselineStudentsT = BigDecimal(24),
            baselineVariance = BigDecimal(25),
            biomassErrorMargin = newBiomassErrorMargin,
            biomassNumPermanentPlots = newBiomassNumPermanentPlots,
            biomassNumTemporaryPlots = newBiomassNumTemporaryPlots,
            biomassStudentsT = newBiomassStudentsT,
            biomassVariance = newBiomassVariance,
            initialPlantingDensity = newInitialPlantingDensity,
            name = newName,
            plantErrorMargin = newPlantErrorMargin,
            plantNumPermanentPlots = newPlantNumPermanentPlots,
            plantNumTemporaryPlots = newPlantNumTemporaryPlots,
            plantStudentsT = newPlantStudentsT,
            plantVariance = newPlantVariance,
            targetPlantDensity = newTargetPlantDensity,
            // Not editable
            boundaryModifiedBy = user.userId,
            boundaryModifiedTime = Instant.ofEpochSecond(5000),
            createdBy = user.userId,
            createdTime = Instant.ofEpochSecond(5000),
            modifiedBy = createdBy,
            modifiedTime = Instant.ofEpochSecond(5000),
        )
      }

      assertEquals(expected, strataDao.fetchOneById(stratumId))
    }

    @Test
    fun `updates full names of substrata if stratum is renamed`() {
      insertPlantingSite()
      val stratumId1 = insertStratum(name = "initial 1")
      val substratumId1 = insertSubstratum(name = "sub 1")
      val substratumId2 = insertSubstratum(name = "sub 2")
      insertStratum(name = "initial 2")
      val substratumId3 = insertSubstratum(name = "sub 3")

      store.updateStratum(stratumId1) { it.copy(name = "renamed") }

      assertEquals(
          mapOf(
              substratumId1 to "renamed-sub 1",
              substratumId2 to "renamed-sub 2",
              substratumId3 to "initial 2-sub 3",
          ),
          substrataDao.findAll().associate { it.id to it.fullName },
      )
    }

    @Test
    fun `updates stratum name in current history entry`() {
      insertPlantingSite()
      val stratumId = insertStratum(name = "initial")
      insertSubstratum(name = "substratum")

      clock.instant = Instant.ofEpochSecond(1000)

      insertPlantingSiteHistory(createdTime = clock.instant)
      val newStratumHistoryId = insertStratumHistory()
      val newSubstratumHistoryId = insertSubstratumHistory()

      val expectedSiteHistory = dslContext.fetch(PLANTING_SITE_HISTORIES)
      val expectedStratumHistory =
          dslContext.fetch(STRATUM_HISTORIES).onEach { record ->
            if (record.id == newStratumHistoryId) {
              record.name = "renamed"
            }
          }
      val expectedSubstratumHistory =
          dslContext.fetch(SUBSTRATUM_HISTORIES).onEach { record ->
            if (record.id == newSubstratumHistoryId) {
              record.fullName = "renamed-substratum"
            }
          }

      store.updateStratum(stratumId) { it.copy(name = "renamed") }

      assertTableEquals(expectedSiteHistory, "Planting site histories should not be affected")
      assertTableEquals(expectedStratumHistory)
      assertTableEquals(expectedSubstratumHistory)
    }

    @Test
    fun `publishes event if densities change`() {
      insertPlantingSite()
      val stratumId =
          insertStratum(
              name = "stratum",
              initialPlantingDensity = BigDecimal(1500),
              targetPlantDensity = null,
          )

      store.updateStratum(stratumId) {
        it.copy(
            initialPlantingDensity = BigDecimal("1750.0"),
            targetPlantDensity = BigDecimal(1200),
        )
      }

      rateLimitedEventPublisher.assertEventPublished(
          StratumDensityUpdatedEvent(
              densityChanges =
                  listOf(
                      DensityChangedEventModel(
                          densityType = DensityChangedEventModel.DensityType.Initial,
                          newDensity = BigDecimal("1750.0"),
                          previousDensity = BigDecimal("1500"),
                          stratumId = stratumId,
                          stratumName = "stratum",
                      ),
                      DensityChangedEventModel(
                          densityType = DensityChangedEventModel.DensityType.Target,
                          newDensity = BigDecimal("1200"),
                          previousDensity = null,
                          stratumId = stratumId,
                          stratumName = "stratum",
                      ),
                  ),
              plantingSiteId = inserted.plantingSiteId,
          )
      )
    }

    @Test
    fun `publishes event with no change for density that stayed the same`() {
      insertPlantingSite()
      val stratumId =
          insertStratum(
              name = "stratum",
              initialPlantingDensity = BigDecimal(1500),
              targetPlantDensity = BigDecimal(1200),
          )

      store.updateStratum(stratumId) { it.copy(targetPlantDensity = null) }

      rateLimitedEventPublisher.assertEventPublished(
          StratumDensityUpdatedEvent(
              densityChanges =
                  listOf(
                      DensityChangedEventModel(
                          densityType = DensityChangedEventModel.DensityType.Target,
                          newDensity = null,
                          previousDensity = BigDecimal("1200"),
                          stratumId = stratumId,
                          stratumName = "stratum",
                      ),
                  ),
              plantingSiteId = inserted.plantingSiteId,
          )
      )
    }

    @Test
    fun `does not publish event if densities are unchanged`() {
      insertPlantingSite()
      val stratumId =
          insertStratum(
              initialPlantingDensity = BigDecimal(1500),
              targetPlantDensity = BigDecimal(1200),
          )

      store.updateStratum(stratumId) {
        it.copy(
            name = "renamed",
            // Same values, different scales
            initialPlantingDensity = BigDecimal("1500.00"),
            targetPlantDensity = BigDecimal("1200.00"),
        )
      }

      eventPublisher.assertEventNotPublished<StratumDensityUpdatedEvent>()
    }

    @Test
    fun `publishes StratumPlotCountsUpdatedEvent when plot counts change`() {
      val plantingSiteId = insertPlantingSite()
      val stratumId = insertStratum(plantNumPermanentPlots = 1, plantNumTemporaryPlots = 1)

      val monitoring = setOf(ObservationType.Monitoring)
      val biomass = setOf(ObservationType.BiomassMeasurements)

      listOf(
              { row: StrataRow -> row.copy(plantNumPermanentPlots = 2) } to monitoring,
              { row: StrataRow -> row.copy(plantNumTemporaryPlots = 2) } to monitoring,
              { row: StrataRow -> row.copy(biomassNumPermanentPlots = 2) } to biomass,
              { row: StrataRow -> row.copy(biomassNumTemporaryPlots = 2) } to biomass,
              { row: StrataRow ->
                row.copy(plantNumPermanentPlots = 3, biomassNumTemporaryPlots = 3)
              } to monitoring + biomass,
          )
          .forEachIndexed { index, (editFunc, expectedTypes) ->
            eventPublisher.clear()

            store.updateStratum(stratumId, editFunc)

            eventPublisher.assertEventPublished(
                StratumPlotCountsUpdatedEvent(plantingSiteId, stratumId, expectedTypes),
                "Edit $index",
            )
          }
    }

    @Test
    fun `throws exception if no permission`() {
      insertPlantingSite()
      val stratumId = insertStratum()

      every { user.canUpdateStratum(stratumId) } returns false

      assertThrows<AccessDeniedException> { store.updateStratum(stratumId) { it } }
    }
  }
}
