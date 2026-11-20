package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.db.tracking.PlotPrecisionLevel
import com.terraformation.backend.db.tracking.PlotSelectionMode
import com.terraformation.backend.db.tracking.tables.pojos.StrataRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATUM_PLOT_SELECTION_PARAMETERS
import com.terraformation.backend.db.tracking.tables.references.SUBSTRATUM_HISTORIES
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.tracking.event.StratumDensityUpdatedEvent
import com.terraformation.backend.tracking.event.StratumPlotCountsUpdatedEvent
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEvent
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEventSettings
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEventSource
import com.terraformation.backend.tracking.event.StratumPlotSelectionSettingsChangedEventValues as Values
import com.terraformation.backend.tracking.model.DensityChangedEventModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.StratumPlotCounts
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreUpdateStratumTest : BasePlantingSiteStoreTest() {

  @Nested
  inner class UpdateStratum {
    @Test
    fun `records only changed settings including mode and level without requiring count changes`() {
      insertPlantingSite()
      insertStratum()
      val initial =
          store.fetchSiteById(inserted.plantingSiteId, PlantingSiteDepth.Stratum).strata.single()
      val counts = initial.plantPlotCounts
      val changes =
          listOf(
              counts.copy(mode = PlotSelectionMode.Custom, plotPrecisionLevel = null),
              counts.copy(mode = PlotSelectionMode.Direct, plotPrecisionLevel = null),
              counts,
              counts.copy(plotPrecisionLevel = PlotPrecisionLevel.High),
              counts.copy(
                  plotPrecisionLevel = PlotPrecisionLevel.High,
                  errorMargin = BigDecimal(21),
                  studentsT = BigDecimal("1.96"),
                  variance = BigDecimal(625),
              ),
          )
      val expectedDeltas =
          listOf(
              Values(mode = counts.mode, plotPrecisionLevel = counts.plotPrecisionLevel) to
                  Values(mode = PlotSelectionMode.Custom),
              Values(mode = PlotSelectionMode.Custom) to Values(mode = PlotSelectionMode.Direct),
              Values(mode = PlotSelectionMode.Direct) to
                  Values(mode = counts.mode, plotPrecisionLevel = counts.plotPrecisionLevel),
              Values(plotPrecisionLevel = counts.plotPrecisionLevel) to
                  Values(plotPrecisionLevel = PlotPrecisionLevel.High),
              Values(
                  errorMargin = counts.errorMargin,
                  studentsT = counts.studentsT,
                  variance = counts.variance.setScale(3),
              ) to
                  Values(
                      errorMargin = BigDecimal(21),
                      studentsT = BigDecimal("1.96"),
                      variance = BigDecimal(625),
                  ),
          )
      for ((changed, delta) in changes.zip(expectedDeltas)) {
        eventPublisher.clear()
        store.updateStratum(inserted.stratumId, mapOf(ObservationType.Monitoring to changed)) { it }
        eventPublisher.assertEventPublished(
            StratumPlotSelectionSettingsChangedEvent(
                organizationId = organizationId,
                plantingSiteId = inserted.plantingSiteId,
                stratumId = inserted.stratumId,
                stratumName = initial.name,
                source = StratumPlotSelectionSettingsChangedEventSource.Terraformation,
                settings =
                    listOf(
                        StratumPlotSelectionSettingsChangedEventSettings(
                            observationType = ObservationType.Monitoring,
                            changedFrom = delta.first,
                            changedTo = delta.second,
                            variance = changed.variance,
                            studentsT = changed.studentsT,
                            numPermanentPlots = changed.numPermanentPlots,
                            numTemporaryPlots = changed.numTemporaryPlots,
                        )
                    ),
            )
        )
        val persisted =
            store.fetchSiteById(inserted.plantingSiteId, PlantingSiteDepth.Stratum).strata.single()
        assertEquals(initial.copy(plantPlotCounts = changed), persisted)
        eventPublisher.clear()
        store.updateStratum(
            inserted.stratumId,
            mapOf(
                ObservationType.Monitoring to changed.copy(variance = changed.variance.setScale(3))
            ),
        ) {
          it
        }
        store.updateStratum(inserted.stratumId) { it }
        eventPublisher.assertEventNotPublished<StratumPlotSelectionSettingsChangedEvent>()
      }
    }

    @ParameterizedTest
    @CsvSource("PrecisionLevel,", "Custom,High", "Direct,Medium")
    fun `rejects precision levels inconsistent with selection mode`(
        mode: PlotSelectionMode,
        level: PlotPrecisionLevel?,
    ) {
      insertPlantingSite()
      insertStratum()

      assertThrows<DataIntegrityViolationException> {
        store.updateStratum(
            inserted.stratumId,
            mapOf(
                ObservationType.Monitoring to
                    StratumPlotCounts(mode = mode, plotPrecisionLevel = level)
            ),
        ) {
          it
        }
      }
    }

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
      val expectedCounts =
          mapOf(
              ObservationType.BaselineSurvey to
                  StratumPlotCounts(
                      mode = PlotSelectionMode.Custom,
                      errorMargin = BigDecimal(21),
                      numPermanentPlots = 0,
                      numTemporaryPlots = 45,
                      studentsT = BigDecimal(24),
                      variance = BigDecimal(25),
                  ),
              ObservationType.BiomassMeasurements to
                  StratumPlotCounts(
                      plotPrecisionLevel = PlotPrecisionLevel.High,
                      errorMargin = newBiomassErrorMargin,
                      numPermanentPlots = newBiomassNumPermanentPlots,
                      numTemporaryPlots = newBiomassNumTemporaryPlots,
                      studentsT = newBiomassStudentsT,
                      variance = newBiomassVariance,
                  ),
              ObservationType.Monitoring to
                  StratumPlotCounts(
                      mode = PlotSelectionMode.Direct,
                      errorMargin = newPlantErrorMargin,
                      numPermanentPlots = newPlantNumPermanentPlots,
                      numTemporaryPlots = newPlantNumTemporaryPlots,
                      studentsT = newPlantStudentsT,
                      variance = newPlantVariance,
                  ),
          )

      val expected =
          StrataRow(
              areaHa = BigDecimal.ONE,
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
              plantingSiteId = plantingSiteId,
              stableId = StableId("initial"),
              targetPlantDensity = newTargetPlantDensity,
          )

      store.updateStratum(stratumId, expectedCounts) {
        it.copy(
            // Editable
            initialPlantingDensity = newInitialPlantingDensity,
            name = newName,
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
      assertTableEquals(
          expectedCounts.map { (type, counts) ->
            dslContext.newRecord(STRATUM_PLOT_SELECTION_PARAMETERS, counts).apply {
              this.stratumId = stratumId
              observationTypeId = type
              plotPrecisionLevelId = counts.plotPrecisionLevel
              plotSelectionModeId = counts.mode
            }
          }
      )
      val stratum = store.fetchSiteById(plantingSiteId, PlantingSiteDepth.Stratum).strata.single()
      assertEquals(expectedCounts, ObservationType.entries.associateWith { stratum.plotCounts(it) })
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
      val baseline = setOf(ObservationType.BaselineSurvey)
      val initialCounts = StratumPlotCounts(numPermanentPlots = 1, numTemporaryPlots = 1)

      listOf(
              mapOf(
                  ObservationType.BaselineSurvey to
                      initialCounts.copy(numPermanentPlots = 0, numTemporaryPlots = 5)
              ) to baseline,
              mapOf(ObservationType.Monitoring to initialCounts.copy(numPermanentPlots = 2)) to
                  monitoring,
              mapOf(
                  ObservationType.Monitoring to
                      initialCounts.copy(numPermanentPlots = 2, numTemporaryPlots = 2)
              ) to monitoring,
              mapOf(
                  ObservationType.BiomassMeasurements to initialCounts.copy(numPermanentPlots = 2)
              ) to biomass,
              mapOf(
                  ObservationType.BiomassMeasurements to
                      initialCounts.copy(numPermanentPlots = 2, numTemporaryPlots = 2)
              ) to biomass,
              mapOf(
                  ObservationType.Monitoring to
                      initialCounts.copy(numPermanentPlots = 3, numTemporaryPlots = 2),
                  ObservationType.BiomassMeasurements to
                      initialCounts.copy(numPermanentPlots = 2, numTemporaryPlots = 3),
              ) to monitoring + biomass,
          )
          .forEachIndexed { index, (counts, expectedTypes) ->
            eventPublisher.clear()

            store.updateStratum(stratumId, counts) { it }

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
