package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.polygon
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon

class PlantingZoneModelTest {
  private val subzones =
      listOf(
          plantingSubzoneModel(id = 1, plots = monitoringPlotModels(10, 11, 12, 13, 14)),
          plantingSubzoneModel(id = 2, plots = monitoringPlotModels(20, 21, 22, 23, 24)),
          plantingSubzoneModel(id = 3, plots = monitoringPlotModels(30, 31, 32, 33, 34)))

  @Nested
  inner class ChooseTemporaryPlots {
    @Test
    fun `does not choose permanent plots`() {
      val model =
          plantingZoneModel(
              numTemporaryPlots = 1,
              subzones = listOf(plantingSubzoneModel(plots = monitoringPlotModels(10, 11))))

      repeatTest {
        val chosenIds =
            model.chooseTemporaryPlots(monitoringPlotIds(10), plantingSubzoneIds(1)).map {
              it.value.toInt()
            }

        assertEquals(listOf(11), chosenIds, "Should not have chosen permanent plot")
      }
    }

    @Test
    fun `does not choose plots that lie partially outside subzone`() {
      val model =
          plantingZoneModel(
              numTemporaryPlots = 1,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          boundary = multiPolygon(polygon(0.0, 0.0, 5.0, 5.0)),
                          id = 1,
                          plots =
                              listOf(
                                  monitoringPlotModel(
                                      boundary = polygon(0.0, 0.0, 1.0, 1.0), id = 10),
                                  monitoringPlotModel(
                                      boundary = polygon(4.5, 0.0, 5.5, 1.0), id = 11),
                              )),
                      plantingSubzoneModel(
                          boundary = multiPolygon(polygon(5.0, 0.0, 10.0, 5.0)),
                          id = 2,
                          plots =
                              listOf(
                                  monitoringPlotModel(
                                      boundary = polygon(6.0, 0.0, 7.0, 1.0), id = 20)))))

      val expected = monitoringPlotIds(10)

      repeatTest {
        val chosenIds = model.chooseTemporaryPlots(emptySet(), plantingSubzoneIds(1))

        assertEquals(expected, chosenIds.toSet())
      }
    }

    @Test
    fun `spreads monitoring plots evenly across subzones`() {
      val model = plantingZoneModel(numTemporaryPlots = 6)

      val availablePlotIds =
          listOf(
              setOf(11, 12, 13, 14),
              setOf(21, 22, 23, 24),
              setOf(30, 31, 32, 33, 34),
          )

      repeatTest {
        val chosenIds =
            model
                .chooseTemporaryPlots(monitoringPlotIds(10, 20), plantingSubzoneIds(1, 2, 3))
                .map { it.value.toInt() }
                .toSet()

        val numChosenPerSubzone = availablePlotIds.map { ids -> ids.intersect(chosenIds).size }

        assertEquals(listOf(2, 2, 2), numChosenPerSubzone, "Number of plots chosen in each subzone")
      }
    }

    @Test
    fun `places excess plots in subzones with fewest permanent plots`() {
      val model = plantingZoneModel(numTemporaryPlots = 5)

      val availablePlotIds =
          listOf(
              setOf(12, 13, 14),
              setOf(21, 22, 23, 24),
              setOf(30, 31, 32, 33, 34),
          )

      repeatTest {
        val chosenIds =
            model
                .chooseTemporaryPlots(monitoringPlotIds(10, 11, 20), plantingSubzoneIds(1, 2, 3))
                .map { it.value.toInt() }
                .toSet()

        val numChosenPerSubzone = availablePlotIds.map { ids -> ids.intersect(chosenIds).size }

        assertEquals(listOf(1, 2, 2), numChosenPerSubzone, "Number of plots chosen in each subzone")
      }
    }

    @Test
    fun `excludes plots that would have been placed in unplanted subzones`() {
      val model = plantingZoneModel(numTemporaryPlots = 5)

      val availablePlotIds =
          listOf(
              setOf(10, 11, 12, 13, 14),
              setOf(20, 21, 22, 23, 24),
              setOf(30, 31, 32, 33, 34),
          )

      repeatTest {
        val chosenIds =
            model
                .chooseTemporaryPlots(emptySet(), plantingSubzoneIds(2, 3))
                .map { it.value.toInt() }
                .toSet()

        val numChosenPerSubzone = availablePlotIds.map { ids -> ids.intersect(chosenIds).size }

        assertEquals(listOf(0, 2, 2), numChosenPerSubzone, "Number of plots chosen in each subzone")
      }
    }

    @Test
    fun `throws exception if no subzones`() {
      val model = plantingZoneModel(numTemporaryPlots = 1, subzones = emptyList())

      assertThrows<IllegalArgumentException> { model.chooseTemporaryPlots(emptySet(), emptySet()) }
    }

    @Test
    fun `throws exception if subzone has too few remaining plots`() {
      val model =
          plantingZoneModel(
              numTemporaryPlots = 2,
              subzones = listOf(plantingSubzoneModel(plots = monitoringPlotModels(10, 11))))

      assertThrows<PlantingSubzoneFullException> {
        model.chooseTemporaryPlots(monitoringPlotIds(10), plantingSubzoneIds(1))
      }
    }
  }

  /**
   * Runs a test multiple times. Monitoring plot selection involves randomness; rather than seeding
   * the random number generator to produce fixed results, we want to check that the expected
   * constraints remain true with a variety of random values.
   *
   * Though this approach can never provide 100% confidence, the repeat count should be high enough,
   * and our tests small enough, to make it very unlikely that any permutations of values aren't
   * covered by a given test run.
   */
  private fun repeatTest(func: () -> Unit) {
    repeat(25) { func() }
  }

  private fun monitoringPlotModel(id: Int = 1, boundary: Polygon = polygon(1.0)) =
      MonitoringPlotModel(
          boundary = boundary,
          id = MonitoringPlotId(id.toLong()),
          fullName = "name",
          name = "name",
          permanentCluster = 1,
          permanentClusterSubplot = 1,
      )

  private fun monitoringPlotIds(vararg id: Int) = id.map { MonitoringPlotId(it.toLong()) }.toSet()

  private fun monitoringPlotModels(vararg id: Int) = id.map { monitoringPlotModel(id = it) }

  private fun plantingSubzoneModel(
      id: Int = 1,
      boundary: MultiPolygon = multiPolygon(1.0),
      plots: List<MonitoringPlotModel> = emptyList()
  ) =
      PlantingSubzoneModel(
          areaHa = BigDecimal.ONE,
          boundary = boundary,
          id = PlantingSubzoneId(id.toLong()),
          fullName = "name",
          name = "name",
          monitoringPlots = plots,
      )

  private fun plantingSubzoneIds(vararg id: Int) = id.map { PlantingSubzoneId(it.toLong()) }.toSet()

  private fun plantingZoneModel(
      numTemporaryPlots: Int,
      subzones: List<PlantingSubzoneModel> = this.subzones
  ) =
      PlantingZoneModel(
          areaHa = BigDecimal.ONE,
          boundary = multiPolygon(1.0),
          errorMargin = BigDecimal.ONE,
          id = PlantingZoneId(1),
          name = "name",
          numPermanentClusters = 1,
          numTemporaryPlots = numTemporaryPlots,
          plantingSubzones = subzones,
          studentsT = BigDecimal.ONE,
          variance = BigDecimal.ONE,
      )
}
