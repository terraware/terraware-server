package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.polygon
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
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.MultiPolygon
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.PrecisionModel

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

      repeatTest {
        val chosenIds =
            model.chooseTemporaryPlots(emptySet(), plantingSubzoneIds(1)).map { it.value.toInt() }

        assertEquals(listOf(10), chosenIds, "Should not have chosen unavailable plot")
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

  @Nested
  inner class FindUnusedSquare {
    private val geometryFactory = GeometryFactory(PrecisionModel(), SRID.LONG_LAT)

    @RepeatedTest(20)
    fun `can place permanent cluster in minimal-size planting zone`() {
      // Boundary shape:
      //
      // +-------+
      // |       |
      // |       +---+
      // |           |
      // +-----------+

      val start = geometryFactory.createPoint(Coordinate(123.0, 45.0))

      val sitePolygon =
          Turtle.makePolygon(start) {
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

      val expected =
          Turtle.makePolygon(start) {
            east(50)
            north(50)
            west(50)
          }

      val actual = zone.findUnusedSquare(start, 50)

      if (!expected.equalsExact(actual, 0.1)) {
        assertEquals(expected, actual)
      }
    }

    @Test
    fun `excludes existing monitoring plots`() {
      // Boundary is a 51x26m square, and there is an existing plot in the southwestern 25x25m.
      val start = geometryFactory.createPoint(Coordinate(123.0, 45.0))

      val siteBoundary =
          Turtle.makeMultiPolygon(start) {
            east(51)
            north(26)
            west(51)
          }
      val existingPlotPolygon =
          Turtle.makePolygon(start) {
            east(25)
            north(25)
            west(25)
          }

      val zone =
          plantingZoneModel(
              boundary = siteBoundary,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          boundary = siteBoundary,
                          plots = listOf(monitoringPlotModel(boundary = existingPlotPolygon)))))

      val expected =
          Turtle.makePolygon(start) {
            moveStartingPoint { east(25) }
            east(25)
            north(25)
            west(25)
          }

      repeat(20) {
        val actual = zone.findUnusedSquare(start, 25)

        if (!expected.equalsExact(actual, 0.1)) {
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

      val start = geometryFactory.createPoint(Coordinate(123.0, 45.0))
      val siteBoundary =
          Turtle.makeMultiPolygon(start) {
            east(21)
            north(21)
            west(21)
          }

      val zone =
          plantingZoneModel(
              boundary = siteBoundary,
              subzones = listOf(plantingSubzoneModel(boundary = siteBoundary, plots = emptyList())))

      val actualCounts =
          (1..numberOfRuns)
              .asSequence()
              .map { zone.findUnusedSquare(start, 10) }
              .also { assertNotNull(it, "Failed to find square") }
              .groupBy { it!!.coordinate }
              .mapValues { it.value.size }

      assertEquals(4, actualCounts.size, "Number of positions of selected squares")

      actualCounts.forEach { (coordinate, count) ->
        if (count !in allowedRange) {
          assertEquals(
              expectedCount,
              count,
              "Approximate count expected for coordinate $coordinate (origin ${start.coordinate})")
        }
      }
    }

    @RepeatedTest(20)
    fun `does exhaustive search of sparse map`() {
      // The site is a series of small triangles spread over a large area, plus one square that's
      // big enough to hold a monitoring plot.
      val edgeMeters = 50000
      val origin = geometryFactory.createPoint(Coordinate(10.0, 20.0))

      val triangles =
          (1..20).map {
            Turtle.makePolygon(origin) {
              moveStartingPoint {
                east(Random.nextInt(edgeMeters - 10))
                north(Random.nextInt(edgeMeters - 10))
              }
              east(10)
              north(10)
            }
          }

      val targetArea =
          Turtle.makePolygon(origin) {
            moveStartingPoint {
              east(Random.nextInt(edgeMeters - 50))
              north(Random.nextInt(edgeMeters - 50))
            }
            east(50)
            north(50)
            west(50)
          }

      val siteBoundary = geometryFactory.createMultiPolygon((triangles + targetArea).toTypedArray())
      val zone =
          plantingZoneModel(
              boundary = siteBoundary,
              subzones = listOf(plantingSubzoneModel(boundary = siteBoundary, plots = emptyList())))

      val square = zone.findUnusedSquare(origin, 25)

      assertNotNull(square, "Unused square")
      if (!square!!.coveredBy(targetArea)) {
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

  private fun monitoringPlotModel(
      id: Int = 1,
      boundary: Polygon = polygon(1.0),
      isAvailable: Boolean = true,
  ) =
      MonitoringPlotModel(
          boundary = boundary,
          id = MonitoringPlotId(id.toLong()),
          isAvailable = isAvailable,
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
          plantingCompletedTime = null,
          monitoringPlots = plots,
      )

  private fun plantingSubzoneIds(vararg id: Int) = id.map { PlantingSubzoneId(it.toLong()) }.toSet()

  private fun plantingZoneModel(
      numTemporaryPlots: Int = 1,
      boundary: MultiPolygon = multiPolygon(1.0),
      subzones: List<PlantingSubzoneModel> = this.subzones,
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
