package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.util.Turtle
import java.math.BigDecimal
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel

class PlantingZoneModelTest {
  private val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)
  private val siteOrigin = geometryFactory.createPoint(Coordinate(12.3, 45.6))

  @Nested
  inner class ChooseTemporaryPlots {
    @Test
    fun `does not choose permanent plots`() {
      val model =
          plantingZoneModel(
              numTemporaryPlots = 1,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          plots =
                              monitoringPlotModels(
                                  permanentIds = listOf(10), temporaryIds = listOf(11)))))

      val expected = listOf(MonitoringPlotId(11))

      repeatTest {
        val actual = model.chooseTemporaryPlots(monitoringPlotIds(10), plantingSubzoneIds(1))
        assertEquals(expected, actual, "Should not have chosen permanent plot")
      }
    }

    @Test
    fun `does not choose unavailable plots`() {
      val model =
          plantingZoneModel(
              numTemporaryPlots = 1,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          plots =
                              listOf(
                                  monitoringPlotModel(10),
                                  monitoringPlotModel(11, isAvailable = false)))))

      val expected = listOf(MonitoringPlotId(10))

      repeatTest {
        val actual = model.chooseTemporaryPlots(emptySet(), plantingSubzoneIds(1))

        assertEquals(expected, actual, "Should not have chosen unavailable plot")
      }
    }

    @Test
    fun `does not choose plots that lie partially outside subzone`() {
      // Zone is 76 meters by 26 meters, split into two 38x26 subzones such that there are three
      // plot locations but the middle one sits on the subzone boundary. Only subzone 1 is planted.
      val zoneBoundary = plantingZoneBoundary(3)
      val subzone1Boundary = Turtle(siteOrigin).makeMultiPolygon { rectangle(38, 26) }
      val subzone2Boundary =
          Turtle(siteOrigin).makeMultiPolygon {
            east(38)
            rectangle(38, 26)
          }
      val subzone1PlotBoundary = Turtle(siteOrigin).makePolygon { square(25) }
      val bothSubzonesPlotBoundary =
          Turtle(siteOrigin).makePolygon {
            east(25)
            square(25)
          }
      val subzone2PlotBoundary =
          Turtle(siteOrigin).makePolygon {
            east(50)
            square(25)
          }

      val model =
          plantingZoneModel(
              boundary = zoneBoundary,
              numTemporaryPlots = 1,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          boundary = subzone1Boundary,
                          id = 1,
                          plots =
                              listOf(
                                  monitoringPlotModel(boundary = subzone1PlotBoundary, id = 10),
                                  monitoringPlotModel(boundary = bothSubzonesPlotBoundary, id = 11),
                              )),
                      plantingSubzoneModel(
                          boundary = subzone2Boundary,
                          id = 2,
                          plots =
                              listOf(
                                  monitoringPlotModel(boundary = subzone2PlotBoundary, id = 20)))))

      val expected = monitoringPlotIds(10)

      repeatTest {
        val chosenIds = model.chooseTemporaryPlots(emptySet(), plantingSubzoneIds(1))

        assertEquals(expected, chosenIds.toSet())
      }
    }

    @Test
    fun `spreads monitoring plots evenly across subzones`() {
      val model =
          plantingZoneModel(
              numTemporaryPlots = 6,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          id = 1,
                          plots =
                              monitoringPlotModels(
                                  permanentIds = listOf(10),
                                  temporaryIds = listOf(11, 12, 13, 14))),
                      plantingSubzoneModel(
                          id = 2,
                          plots =
                              monitoringPlotModels(
                                  permanentIds = listOf(20),
                                  temporaryIds = listOf(21, 22, 23, 24))),
                      plantingSubzoneModel(
                          id = 3,
                          plots = monitoringPlotModels(temporaryIds = listOf(30, 31, 32, 33, 34)))))

      val availablePlotIds =
          listOf(
              monitoringPlotIds(11, 12, 13, 14),
              monitoringPlotIds(21, 22, 23, 24),
              monitoringPlotIds(30, 31, 32, 33, 34),
          )

      repeatTest {
        val chosenIds =
            model
                .chooseTemporaryPlots(monitoringPlotIds(10, 20), plantingSubzoneIds(1, 2, 3))
                .toSet()

        val numChosenPerSubzone = availablePlotIds.map { ids -> ids.intersect(chosenIds).size }

        assertEquals(listOf(2, 2, 2), numChosenPerSubzone, "Number of plots chosen in each subzone")
      }
    }

    @Test
    fun `places excess plots in subzones with fewest permanent plots`() {
      val model =
          plantingZoneModel(
              numTemporaryPlots = 5,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          id = 1,
                          plots =
                              monitoringPlotModels(
                                  permanentIds = listOf(10, 11),
                                  temporaryIds = listOf(12, 13, 14))),
                      plantingSubzoneModel(
                          id = 2,
                          plots =
                              monitoringPlotModels(
                                  permanentIds = listOf(20),
                                  temporaryIds = listOf(21, 22, 23, 24))),
                      plantingSubzoneModel(
                          id = 3,
                          plots = monitoringPlotModels(temporaryIds = listOf(30, 31, 32, 33, 34)))))

      val availablePlotIds =
          listOf(
              monitoringPlotIds(12, 13, 14),
              monitoringPlotIds(21, 22, 23, 24),
              monitoringPlotIds(30, 31, 32, 33, 34),
          )

      repeatTest {
        val chosenIds =
            model
                .chooseTemporaryPlots(monitoringPlotIds(10, 11, 20), plantingSubzoneIds(1, 2, 3))
                .toSet()

        val numChosenPerSubzone = availablePlotIds.map { ids -> ids.intersect(chosenIds).size }

        assertEquals(listOf(1, 2, 2), numChosenPerSubzone, "Number of plots chosen in each subzone")
      }
    }

    @Test
    fun `excludes plots that would have been placed in unplanted subzones`() {
      val model =
          plantingZoneModel(
              numTemporaryPlots = 5,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          id = 1,
                          plots = monitoringPlotModels(temporaryIds = listOf(10, 11, 12, 13, 14))),
                      plantingSubzoneModel(
                          id = 2,
                          plots = monitoringPlotModels(temporaryIds = listOf(20, 21, 22, 23, 24))),
                      plantingSubzoneModel(
                          id = 3,
                          plots = monitoringPlotModels(temporaryIds = listOf(30, 31, 32, 33, 34)))))

      val availablePlotIds =
          listOf(
              monitoringPlotIds(10, 11, 12, 13, 14),
              monitoringPlotIds(20, 21, 22, 23, 24),
              monitoringPlotIds(30, 31, 32, 33, 34),
          )

      repeatTest {
        val chosenIds = model.chooseTemporaryPlots(emptySet(), plantingSubzoneIds(2, 3)).toSet()

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
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          plots =
                              monitoringPlotModels(
                                  permanentIds = listOf(10), temporaryIds = listOf(11)))))

      assertThrows<PlantingSubzoneFullException> {
        model.chooseTemporaryPlots(monitoringPlotIds(10), plantingSubzoneIds(1))
      }
    }
  }

  @Nested
  inner class FindUnusedSquare {
    @RepeatedTest(20)
    fun `can place permanent cluster in minimal-size planting zone`() {
      // Boundary shape:
      //
      // +-------+
      // |       |
      // |       +---+
      // |           |
      // +-----------+

      val sitePolygon =
          Turtle(siteOrigin).makePolygon {
            startDrawing()
            east(76)
            north(26)
            west(25)
            north(25)
            west(51)
          }

      val siteBoundary = geometryFactory.createMultiPolygon(arrayOf(sitePolygon))

      val zone =
          plantingZoneModel(
              boundary = siteBoundary,
              subzones = listOf(plantingSubzoneModel(boundary = siteBoundary, plots = emptyList())))

      val expected = Turtle(siteOrigin).makePolygon { square(50) }

      val actual = zone.findUnusedSquare(siteOrigin, 50)

      if (!expected.equalsExact(actual, 0.1)) {
        assertEquals(expected, actual)
      }
    }

    @Test
    fun `excludes permanent monitoring plots`() {
      // Boundary is a 51x26m square, and there is an existing plot in the southwestern 25x25m.
      val siteBoundary = Turtle(siteOrigin).makeMultiPolygon { rectangle(51, 26) }
      val existingPlotPolygon = Turtle(siteOrigin).makePolygon { square(25) }

      val zone =
          plantingZoneModel(
              boundary = siteBoundary,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          boundary = siteBoundary,
                          plots =
                              listOf(
                                  monitoringPlotModel(
                                      boundary = existingPlotPolygon, permanentCluster = 1)))))

      val expected =
          Turtle(siteOrigin).makePolygon {
            east(25)
            square(25)
          }

      repeat(20) {
        val actual = zone.findUnusedSquare(siteOrigin, 25)

        if (!expected.equalsExact(actual, 0.000001)) {
          assertEquals(expected, actual)
        }
      }
    }

    @Test
    fun `all grid positions are equally likely to be chosen`() {
      // Boundary is a 21x21 square, and we'll be placing a 10m square in it, so there should be
      // 4 possible positions.
      //
      // Need to do enough runs that the random selection is highly unlikely to have huge spikes,
      // but not so many that the test takes an unacceptably long time to run. If you lower this,
      // you will probably want to increase allowedVariancePercent.
      //
      // Tested that this value is high enough by changing @Test to @RepeatedTest(5000).
      val numberOfRuns = 2000
      val expectedCount = numberOfRuns / 4

      // We expect each position to be chosen roughly the same number of times, but with some
      // variation allowed because it's a random selection.
      val allowedVariancePercent = 25.0
      val allowedVariance = (expectedCount * allowedVariancePercent / 100.0).toInt()
      val allowedRange = IntRange(expectedCount - allowedVariance, expectedCount + allowedVariance)

      val siteBoundary = Turtle(siteOrigin).makeMultiPolygon { square(21) }

      val zone =
          plantingZoneModel(
              boundary = siteBoundary,
              subzones = listOf(plantingSubzoneModel(boundary = siteBoundary, plots = emptyList())))

      val actualCounts =
          (1..numberOfRuns)
              .asSequence()
              .map { zone.findUnusedSquare(siteOrigin, 10) }
              .also { assertNotNull(it, "Failed to find square") }
              .groupBy { it!!.coordinate }
              .mapValues { it.value.size }

      assertEquals(4, actualCounts.size, "Number of positions of selected squares")

      actualCounts.forEach { (coordinate, count) ->
        if (count !in allowedRange) {
          assertEquals(
              expectedCount,
              count,
              "Approximate count expected for coordinate $coordinate (origin ${siteOrigin.coordinate})")
        }
      }
    }

    @RepeatedTest(20)
    fun `does exhaustive search of sparse map`() {
      // The site is a series of small triangles spread over a large area, plus one square that's
      // big enough to hold a monitoring plot.
      val edgeMeters = 50000

      val triangles =
          (1..20).map {
            Turtle(siteOrigin).makePolygon {
              east(Random.nextInt(edgeMeters - 10))
              north(Random.nextInt(edgeMeters - 10))

              startDrawing()
              east(10)
              north(10)
            }
          }

      val targetArea =
          Turtle(siteOrigin).makePolygon {
            east(Random.nextInt(edgeMeters - 51))
            north(Random.nextInt(edgeMeters - 51))
            square(51)
          }

      val siteBoundary = geometryFactory.createMultiPolygon((triangles + targetArea).toTypedArray())
      val zone =
          plantingZoneModel(
              boundary = siteBoundary,
              subzones = listOf(plantingSubzoneModel(boundary = siteBoundary, plots = emptyList())))

      val square = zone.findUnusedSquare(siteOrigin, 25)
      assertNotNull(square, "Unused square")

      if (square!!.intersection(targetArea).area < square.area * 0.99999) {
        assertEquals(targetArea, square, "Should be contained in hole")
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

  /**
   * Returns the boundary of a test monitoring plot based on its ID. The 10s digit of the ID is
   * assumed to be the subzone ID and the 1s digit is assumed to be a position in the subzone. The
   * positions are laid out as follows:
   *
   *     4
   *     3  2
   *     0  1
   */
  private fun monitoringPlotBoundary(id: Int): Polygon {
    val subzoneId = id / 10
    val plotNumber = id.rem(10)

    return Turtle(siteOrigin).makePolygon {
      // Subzone corner
      east(subzoneId * 51)
      when (plotNumber) {
        0 -> Unit
        1 -> east(25)
        2 -> {
          east(25)
          north(25)
        }
        3 -> north(25)
        4 -> north(50)
        else -> throw IllegalArgumentException("Invalid last digit $plotNumber of test plot")
      }

      square(25)
    }
  }

  private fun monitoringPlotModel(
      id: Int = 1,
      boundary: Polygon = monitoringPlotBoundary(id),
      isAvailable: Boolean = true,
      permanentCluster: Int? = null,
  ): MonitoringPlotModel {
    return MonitoringPlotModel(
        boundary = boundary,
        id = MonitoringPlotId(id.toLong()),
        isAvailable = isAvailable,
        fullName = "name",
        name = "name",
        permanentCluster = permanentCluster,
        permanentClusterSubplot = if (permanentCluster != null) 1 else null,
    )
  }

  private fun monitoringPlotIds(vararg id: Int) = id.map { MonitoringPlotId(it.toLong()) }.toSet()

  private fun monitoringPlotModels(
      permanentIds: List<Int> = emptyList(),
      temporaryIds: List<Int> = emptyList()
  ): List<MonitoringPlotModel> {
    return permanentIds.map { monitoringPlotModel(id = it, permanentCluster = 1) } +
        temporaryIds.map { monitoringPlotModel(id = it, permanentCluster = null) }
  }

  /**
   * Returns the boundary for a sample subzone. Subzones are arranged in a row from west to east and
   * each one has room for 5 monitoring plots in the layout defined by [monitoringPlotBoundary],
   * plus a 1-meter margin to account for floating-point inaccuracy.
   */
  private fun plantingSubzoneBoundary(id: Int): MultiPolygon {
    return Turtle(siteOrigin).makeMultiPolygon {
      east(id * 51)

      startDrawing()
      east(51)
      north(51)
      west(25)
      north(25)
      west(26)
    }
  }

  private fun plantingSubzoneModel(
      id: Int = 1,
      boundary: MultiPolygon = plantingSubzoneBoundary(id),
      plots: List<MonitoringPlotModel> = emptyList()
  ) =
      PlantingSubzoneModel(
          areaHa = BigDecimal.ONE,
          boundary = boundary,
          id = PlantingSubzoneId(id.toLong()),
          fullName = "name",
          name = "name",
          plantingCompletedTime = null,
          monitoringPlots = plots,
      )

  private fun plantingSubzoneIds(vararg id: Int) = id.map { PlantingSubzoneId(it.toLong()) }.toSet()

  /**
   * Returns the boundary for a sample planting zone that contains some number of 51x76 meter
   * subzones laid out west to east.
   */
  private fun plantingZoneBoundary(numSubzones: Int): MultiPolygon {
    if (numSubzones <= 0) {
      return multiPolygon(1.0)
    }

    val combinedBoundary =
        (1..numSubzones)
            .map { plantingSubzoneBoundary(it) }
            .reduce { acc: Geometry, subzone: Geometry -> acc.union(subzone) }

    return when (combinedBoundary) {
      is MultiPolygon -> combinedBoundary
      is Polygon -> geometryFactory.createMultiPolygon(arrayOf(combinedBoundary))
      else -> throw IllegalStateException("Boundary is a ${combinedBoundary.javaClass.simpleName}")
    }
  }

  private fun plantingZoneModel(
      numTemporaryPlots: Int = 1,
      subzones: List<PlantingSubzoneModel>,
      boundary: MultiPolygon = plantingZoneBoundary(subzones.size),
  ) =
      PlantingZoneModel(
          areaHa = BigDecimal.ONE,
          boundary = boundary,
          errorMargin = BigDecimal.ONE,
          extraPermanentClusters = 0,
          id = PlantingZoneId(1),
          name = "name",
          numPermanentClusters = 1,
          numTemporaryPlots = numTemporaryPlots,
          plantingSubzones = subzones,
          studentsT = BigDecimal.ONE,
          targetPlantingDensity = BigDecimal.ONE,
          variance = BigDecimal.ONE,
      )
}
