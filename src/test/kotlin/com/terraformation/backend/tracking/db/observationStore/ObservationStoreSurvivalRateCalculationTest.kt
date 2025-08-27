package com.terraformation.backend.tracking.db.observationStore

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesIdConverter
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.db.tracking.RecordedPlantStatus
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.pojos.RecordedPlantsRow
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_PLOT_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SITE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_SUBZONE_SPECIES_TOTALS
import com.terraformation.backend.db.tracking.tables.references.OBSERVED_ZONE_SPECIES_TOTALS
import com.terraformation.backend.point
import java.math.BigDecimal
import java.time.Instant
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.containsKey
import kotlin.math.roundToInt
import org.jooq.TableField
import org.jooq.impl.SQLDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ObservationStoreSurvivalRateCalculationTest : BaseObservationStoreTest() {
  private lateinit var observationId: ObservationId
  private lateinit var plotId: MonitoringPlotId
  private lateinit var subzoneId: PlantingSubzoneId
  private lateinit var zoneId: PlantingZoneId
  private val observedTime = Instant.ofEpochSecond(1)

  @BeforeEach
  fun insertDetailedSiteAndObservation() {
    zoneId = insertPlantingZone()
    subzoneId = insertPlantingSubzone()
    plotId = insertMonitoringPlot()
    observationId = insertObservation()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)
  }

  @Test
  fun `does not calculate survival rate if no plot density`() {
    val speciesId = insertSpecies()
    val recordedPlants =
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId,
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                gpsCoordinates = point(2),
                statusId = RecordedPlantStatus.Dead,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Other,
                gpsCoordinates = point(3),
                speciesName = "Who knows",
                statusId = RecordedPlantStatus.Existing,
            ),
        )
    store.completePlot(observationId, plotId, emptySet(), "Notes", observedTime, recordedPlants)

    assertSurvivalRates(
        listOf(
            mapOf(plotId to mapOf(speciesId to null)),
            mapOf(subzoneId to mapOf(speciesId to null)),
            mapOf(zoneId to mapOf(speciesId to null)),
            mapOf(plantingSiteId to mapOf(speciesId to null)),
        ),
        "All survival rates should be null",
    )
  }

  @Test
  fun `survival rate is calculated for a plot using plot density`() {
    val speciesId1 = insertSpecies()
    insertPlotT0Density(plotDensity = BigDecimal.valueOf(11))

    val recordedPlants =
        listOf(
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Known,
                gpsCoordinates = point(1),
                speciesId = speciesId1,
                statusId = RecordedPlantStatus.Live,
            ),
            RecordedPlantsRow(
                certaintyId = RecordedSpeciesCertainty.Unknown,
                gpsCoordinates = point(2),
                statusId = RecordedPlantStatus.Dead,
            ),
        )
    store.completePlot(observationId, plotId, emptySet(), "Notes", observedTime, recordedPlants)

    assertSurvivalRates(
        listOf(
            mapOf(plotId to mapOf(speciesId1 to (100.0 * 1 / 11))),
            mapOf(subzoneId to mapOf(speciesId1 to (100.0 * 1 / 11))),
            mapOf(zoneId to mapOf(speciesId1 to (100.0 * 1 / 11))),
            mapOf(plantingSiteId to mapOf(speciesId1 to (100.0 * 1 / 11))),
        ),
        "Plot survival rates",
    )
  }

  @Test
  fun `survival rate is calculated for a subzone using all plots`() {
    val speciesId1 = insertSpecies()
    val speciesId2 = insertSpecies()
    val speciesId3 = insertSpecies()
    insertPlotT0Density(speciesId = speciesId1, plotDensity = BigDecimal.valueOf(15))
    insertPlotT0Density(speciesId = speciesId2, plotDensity = BigDecimal.valueOf(23))
    insertPlotT0Density(speciesId = speciesId3, plotDensity = BigDecimal.valueOf(31))

    // plot with no density for species 3
    val plot2 = insertMonitoringPlot()
    insertObservationPlot(claimedBy = user.userId, isPermanent = true)
    insertPlotT0Density(speciesId = speciesId1, plotDensity = BigDecimal.valueOf(9))
    insertPlotT0Density(speciesId = speciesId2, plotDensity = BigDecimal.valueOf(19))

    val plot1Plants =
        createPlantsRows(
            mapOf(speciesId1 to 9, speciesId2 to 18, speciesId3 to 27),
            RecordedPlantStatus.Live,
        ) +
            createPlantsRows(
                mapOf(speciesId1 to 1, speciesId2 to 1, speciesId3 to 2),
                RecordedPlantStatus.Dead,
            ) +
            listOf(
                RecordedPlantsRow(
                    certaintyId = RecordedSpeciesCertainty.Unknown,
                    gpsCoordinates = point(100),
                    statusId = RecordedPlantStatus.Dead,
                ),
                RecordedPlantsRow(
                    certaintyId = RecordedSpeciesCertainty.Other,
                    gpsCoordinates = point(101),
                    speciesName = "Who knows",
                    statusId = RecordedPlantStatus.Existing,
                ),
            )
    store.completePlot(observationId, plotId, emptySet(), "Notes1", observedTime, plot1Plants)

    val plot1SurvivalRates: Map<Any, Map<SpeciesId, Double?>> =
        mapOf(
            plotId to
                mapOf(
                    speciesId1 to (100.0 * 9 / 15),
                    speciesId2 to (100.0 * 18 / 23),
                    speciesId3 to (100.0 * 27 / 31),
                )
        )
    val survivalRates1 =
        mapOf(
            speciesId1 to (100.0 * 9 / (15 + 9)),
            speciesId2 to (100.0 * 18 / (23 + 19)),
            speciesId3 to (100.0 * 27 / 31),
        )

    assertSurvivalRates(
        listOf(
            plot1SurvivalRates,
            mapOf(subzoneId to survivalRates1),
            mapOf(zoneId to survivalRates1),
            mapOf(plantingSiteId to survivalRates1),
        ),
        "Plot 1 survival rates",
    )

    val plot2Plants =
        createPlantsRows(
            mapOf(speciesId1 to 10, speciesId2 to 17, speciesId3 to 25),
            RecordedPlantStatus.Live,
        ) +
            createPlantsRows(
                mapOf(speciesId1 to 0, speciesId2 to 1, speciesId3 to 1),
                RecordedPlantStatus.Dead,
            ) +
            listOf(
                RecordedPlantsRow(
                    certaintyId = RecordedSpeciesCertainty.Other,
                    gpsCoordinates = point(102),
                    speciesName = "Who knows",
                    statusId = RecordedPlantStatus.Existing,
                )
            )
    store.completePlot(observationId, plot2, emptySet(), "Notes2", observedTime, plot2Plants)

    val plot2SurvivalRates: Map<Any, Map<SpeciesId, Double?>> =
        mapOf(
            plot2 to
                mapOf(
                    speciesId1 to (100.0 * 10 / 9),
                    speciesId2 to (100.0 * 17 / 19),
                    speciesId3 to null,
                )
        )
    val survivalRates1And2 =
        mapOf(
            speciesId1 to (100.0 * (9 + 10) / (15 + 9)),
            speciesId2 to (100.0 * (17 + 18) / (23 + 19)),
            speciesId3 to (100.0 * (27 + 25) / 31),
        )
    assertSurvivalRates(
        listOf(
            plot1SurvivalRates + plot2SurvivalRates,
            mapOf(subzoneId to survivalRates1And2),
            mapOf(zoneId to survivalRates1And2),
            mapOf(plantingSiteId to survivalRates1And2),
        ),
        "Plots 1 and 2 survival rates",
    )
  }

  @Test
  fun `survival rate is calculated for a site using all data`() {
    val species1 = insertSpecies()
    val species2 = insertSpecies()
    val species3 = insertSpecies()
    fun insertPlotWithDensities(
        subzoneId: PlantingSubzoneId,
        species1Density: Double,
        species2Density: Double,
        species3Density: Double,
    ): MonitoringPlotId {
      val plotId = insertMonitoringPlot(plantingSubzoneId = subzoneId)
      insertObservationPlot(claimedBy = user.userId, isPermanent = true)
      insertPlotT0Density(speciesId = species1, plotDensity = BigDecimal.valueOf(species1Density))
      insertPlotT0Density(speciesId = species2, plotDensity = BigDecimal.valueOf(species2Density))
      insertPlotT0Density(speciesId = species3, plotDensity = BigDecimal.valueOf(species3Density))
      return plotId
    }
    fun completePlot(
        plotId: MonitoringPlotId,
        species1Count: Int,
        species2Count: Int,
        species3Count: Int,
    ) {
      store.completePlot(
          observationId,
          plotId,
          emptySet(),
          "Notes1",
          observedTime,
          createPlantsRows(
              mapOf(
                  species1 to species1Count,
                  species2 to species2Count,
                  species3 to species3Count,
              ),
              RecordedPlantStatus.Live,
          ),
      )
    }
    val zone1 = insertPlantingZone()
    val subzone1 = insertPlantingSubzone()
    val subzone2 = insertPlantingSubzone()
    val zone2 = insertPlantingZone()
    val subzone3 = insertPlantingSubzone()
    val subzone4 = insertPlantingSubzone()
    val zone3 = insertPlantingZone()
    val subzone5 = insertPlantingSubzone()
    val subzone6 = insertPlantingSubzone()

    val plot1 = insertPlotWithDensities(subzone1, 17.0, 26.0, 5.0)
    val plot2 = insertPlotWithDensities(subzone1, 11.52, 44.37, 42.21)
    val plot3 = insertPlotWithDensities(subzone1, 10.35, 40.41, 21.87)
    val plot4 = insertPlotWithDensities(subzone2, 12.33, 41.85, 9.45)
    val plot5 = insertPlotWithDensities(subzone2, 17.55, 23.4, 29.88)
    val plot6 = insertPlotWithDensities(subzone3, 1.0, 3.0, 11.0)
    val plot7 = insertPlotWithDensities(subzone3, 11.0, 24.0, 17.0)
    val plot8 = insertPlotWithDensities(subzone3, 28.98, 20.79, 41.31)
    val plot9 = insertPlotWithDensities(subzone4, 16.83, 12.06, 23.94)
    val plot10 = insertPlotWithDensities(subzone4, 28.0, 2.0, 28.0)
    val plot11 = insertPlotWithDensities(subzone4, 41.85, 28.53, 16.92)
    val plot12 = insertPlotWithDensities(subzone4, 16.0, 23.0, 31.0)
    val plot13 = insertPlotWithDensities(subzone5, 22.0, 10.0, 31.0)
    val plot14 = insertPlotWithDensities(subzone5, 26.0, 3.0, 1.0)
    val plot15 = insertPlotWithDensities(subzone5, 15.66, 10.35, 18.81)
    val plot16 = insertPlotWithDensities(subzone6, 12.06, 33.12, 17.91)
    val plot17 = insertPlotWithDensities(subzone6, 13.23, 35.37, 21.42)
    val plot18 = insertPlotWithDensities(subzone6, 24.0, 20.0, 15.0)
    val plot19 = insertPlotWithDensities(subzone6, 18.0, 15.0, 6.0)
    val plot20 = insertPlotWithDensities(subzone6, 21.0, 20.0, 12.0)

    completePlot(plot1, 42, 8, 14)
    completePlot(plot2, 24, 15, 41)
    completePlot(plot3, 7, 45, 36)
    completePlot(plot4, 31, 2, 40)
    completePlot(plot5, 52, 41, 53)
    completePlot(plot6, 33, 22, 40)
    completePlot(plot7, 38, 7, 43)
    completePlot(plot8, 39, 14, 33)
    completePlot(plot9, 35, 48, 2)
    completePlot(plot10, 31, 5, 2)
    completePlot(plot11, 53, 24, 41)
    completePlot(plot12, 47, 27, 12)
    completePlot(plot13, 30, 25, 58)
    completePlot(plot14, 17, 18, 4)
    completePlot(plot15, 33, 36, 22)
    completePlot(plot16, 45, 30, 0)
    completePlot(plot17, 13, 49, 26)
    completePlot(plot18, 44, 40, 10)
    completePlot(plot19, 13, 39, 28)
    completePlot(plot20, 46, 39, 35)

    assertSurvivalRates(
        listOf(
            mapOf(
                plot1 to mapOf(species1 to 247, species2 to 31, species3 to 280),
                plot2 to mapOf(species1 to 208, species2 to 34, species3 to 97),
                plot3 to mapOf(species1 to 68, species2 to 111, species3 to 165),
                plot4 to mapOf(species1 to 251, species2 to 5, species3 to 423),
                plot5 to mapOf(species1 to 296, species2 to 175, species3 to 177),
                plot6 to mapOf(species1 to 3300, species2 to 733, species3 to 364),
                plot7 to mapOf(species1 to 345, species2 to 29, species3 to 253),
                plot8 to mapOf(species1 to 135, species2 to 67, species3 to 80),
                plot9 to mapOf(species1 to 208, species2 to 398, species3 to 8),
                plot10 to mapOf(species1 to 111, species2 to 250, species3 to 7),
                plot11 to mapOf(species1 to 127, species2 to 84, species3 to 242),
                plot12 to mapOf(species1 to 294, species2 to 117, species3 to 39),
                plot13 to mapOf(species1 to 136, species2 to 250, species3 to 187),
                plot14 to mapOf(species1 to 65, species2 to 600, species3 to 400),
                plot15 to mapOf(species1 to 211, species2 to 348, species3 to 117),
                // species 3 is missing because count is 0 in observation so it has no totals row
                plot16 to mapOf(species1 to 373, species2 to 91),
                plot17 to mapOf(species1 to 98, species2 to 139, species3 to 121),
                plot18 to mapOf(species1 to 183, species2 to 200, species3 to 67),
                plot19 to mapOf(species1 to 72, species2 to 260, species3 to 467),
                plot20 to mapOf(species1 to 219, species2 to 195, species3 to 292),
            ),
            mapOf(
                subzone1 to mapOf(species1 to 188, species2 to 61, species3 to 132),
                subzone2 to mapOf(species1 to 278, species2 to 66, species3 to 236),
                subzone3 to mapOf(species1 to 268, species2 to 90, species3 to 167),
                subzone4 to mapOf(species1 to 162, species2 to 159, species3 to 57),
                subzone5 to mapOf(species1 to 126, species2 to 338, species3 to 165),
                subzone6 to mapOf(species1 to 182, species2 to 160, species3 to 137),
            ),
            mapOf(
                zone1 to mapOf(species1 to 227, species2 to 63, species3 to 170),
                zone2 to mapOf(species1 to 192, species2 to 130, species3 to 102),
                zone3 to mapOf(species1 to 159, species2 to 188, species3 to 149),
            ),
            mapOf(
                plantingSiteId to mapOf(species1 to 185, species2 to 122, species3 to 135),
            ),
        ),
        "Site level survival rates",
    )
  }

  private var lastCoord: Int = 1

  private fun createPlantsRows(
      counts: Map<SpeciesId, Int>,
      status: RecordedPlantStatus,
  ): List<RecordedPlantsRow> {
    return counts.flatMap { (speciesId, count) ->
      (1..count).map {
        RecordedPlantsRow(
            certaintyId = RecordedSpeciesCertainty.Known,
            gpsCoordinates = point(lastCoord++),
            speciesId = speciesId,
            statusId = status,
        )
      }
    }
  }

  private fun assertSurvivalRates(
      expected: List<Map<Any, Map<SpeciesId, Number?>>>,
      message: String,
  ) {
    val idFields =
        listOf(
            OBSERVED_PLOT_SPECIES_TOTALS.MONITORING_PLOT_ID,
            OBSERVED_SUBZONE_SPECIES_TOTALS.PLANTING_SUBZONE_ID,
            OBSERVED_ZONE_SPECIES_TOTALS.PLANTING_ZONE_ID,
            OBSERVED_SITE_SPECIES_TOTALS.PLANTING_SITE_ID,
        )

    val levelNames = listOf("Plot", "Subzone", "Zone", "Site")

    expected.forEachIndexed { index, expectedByIdMap ->
      val actualByIdMap = fetchSurvivalRatesPerSpecies(idFields[index])

      val allIdsMatch =
          actualByIdMap.all { (id, actualSpeciesMap) ->
            val expectedSpeciesMap = expectedByIdMap[id] ?: return@all false

            actualSpeciesMap.all { (speciesId, actualRate) ->
              val expectedRate = expectedSpeciesMap[speciesId]
              when {
                expectedRate == null -> actualRate == null
                actualRate == null -> false
                expectedRate is Double -> expectedRate.roundToInt() == actualRate
                else -> expectedRate == actualRate
              }
            } &&
                expectedSpeciesMap.all { (speciesId, _) -> actualSpeciesMap.containsKey(speciesId) }
          } && expectedByIdMap.all { (id, _) -> actualByIdMap.containsKey(id) }

      if (!allIdsMatch) {
        val expectedString =
            expectedByIdMap.entries.joinToString("\n") { (id, speciesMap) ->
              "${levelNames[index]} $id: {${speciesMap.entries.joinToString(", ") { "SpeciesId: ${it.key} -> SurvivalRate: ${it.value?.let { value -> if (value is Double) value.roundToInt() else value }}" }}}"
            }
        val actualString =
            actualByIdMap.entries.joinToString("\n") { (id, speciesMap) ->
              "${levelNames[index]} $id: {${speciesMap.entries.joinToString(", ") { "SpeciesId: ${it.key} -> SurvivalRate: ${it.value}" }}}"
            }
        assertEquals(expectedString, actualString, "$message - ${levelNames[index]}")
      }
    }
  }

  private fun <ID> fetchSurvivalRatesPerSpecies(
      idField: TableField<*, ID>
  ): Map<ID, Map<SpeciesId, Int?>> {
    val table = idField.table!!
    val speciesIdField =
        table.field("species_id", SQLDataType.BIGINT.asConvertedDataType(SpeciesIdConverter()))!!
    val survivalRateField = table.field("survival_rate", Int::class.java)!!

    return dslContext
        .select(idField, speciesIdField, survivalRateField)
        .from(table)
        .where(speciesIdField.isNotNull)
        .fetch()
        .filter { it[idField] != null }
        .groupBy { it[idField]!! }
        .mapValues { (_, records) ->
          records.associate { record -> record[speciesIdField]!! to record[survivalRateField] }
        }
  }
}
