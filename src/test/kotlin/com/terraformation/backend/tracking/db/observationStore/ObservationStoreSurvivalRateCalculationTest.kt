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
    insertObservationPlot(claimedBy = user.userId, claimedTime = Instant.EPOCH, isPermanent = true)
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
    insertObservationPlot(
        claimedBy = user.userId,
        claimedTime = Instant.EPOCH,
        isPermanent = true,
    )
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
      expected: List<Map<Any, Map<SpeciesId, Double?>>>,
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
                else -> expectedRate.roundToInt() == actualRate
              }
            } &&
                expectedSpeciesMap.all { (speciesId, _) -> actualSpeciesMap.containsKey(speciesId) }
          } && expectedByIdMap.all { (id, _) -> actualByIdMap.containsKey(id) }

      if (!allIdsMatch) {
        val expectedString =
            expectedByIdMap.entries.joinToString("\n") { (id, speciesMap) ->
              "${levelNames[index]} $id: {${speciesMap.entries.joinToString(", ") { "SpeciesId: ${it.key} -> SurvivalRate: ${it.value?.let { value -> value.roundToInt() }}" }}}"
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
