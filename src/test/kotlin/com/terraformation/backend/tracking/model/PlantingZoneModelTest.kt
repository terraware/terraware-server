package com.terraformation.backend.tracking.model

import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.SRID
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.db.tracking.PlantingSubzoneId
import com.terraformation.backend.db.tracking.PlantingZoneId
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.toMultiPolygon
import java.math.BigDecimal
import java.time.Instant
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
  inner class ChoosePermanentPlots {
    @Test
    fun `only chooses plots that lie in requested subzones`() {
      val model =
          plantingZoneModel(
              numPermanentPlots = 4,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          id = 1,
                          plots = monitoringPlotModels(permanentIds = listOf(1, 2)),
                      ),
                      plantingSubzoneModel(
                          id = 2,
                          plots = monitoringPlotModels(permanentIds = listOf(3, 4)),
                      ),
                  ),
          )

      assertSetEquals(
          setOf(MonitoringPlotId(3), MonitoringPlotId(4)),
          model.choosePermanentPlots(plantingSubzoneIds(2)),
      )
    }
  }

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
                                  permanentIds = listOf(10, 11, 12, 13),
                                  temporaryIds = listOf(14),
                              )
                      )
                  ),
          )

      val expected = listOf(MonitoringPlotId(14))

      repeatTest {
        val actual =
            model.chooseTemporaryPlots(plantingSubzoneIds(1), siteOrigin).map {
              model.findMonitoringPlot(it)?.id
            }
        assertEquals(expected, actual, "Should not have chosen permanent plot")
      }
    }

    @Test
    fun `can choose plots with permanent indexes that are not permanent currently`() {
      val model =
          plantingZoneModel(
              numTemporaryPlots = 1,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          plots = listOf(monitoringPlotModel(10, permanentIndex = 2))
                      )
                  ),
          )

      repeatTest {
        val indexOfSelectedPlot =
            model
                .chooseTemporaryPlots(plantingSubzoneIds(1), siteOrigin)
                .mapNotNull { model.findMonitoringPlot(it) }
                .single()
                .permanentIndex

        assertEquals(2, indexOfSelectedPlot, "Should have chosen plot with unused permanent index")
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
                                  monitoringPlotModel(11, isAvailable = false),
                              )
                      )
                  ),
          )

      val unexpected = listOf(MonitoringPlotId(11))

      repeatTest {
        val actual =
            model.chooseTemporaryPlots(plantingSubzoneIds(1), siteOrigin).map {
              model.findMonitoringPlot(it)?.id
            }

        assertNotEquals(unexpected, actual, "Should not have chosen unavailable plot")
      }
    }

    @Test
    fun `does not choose plots that lie partially outside subzone`() {
      // Zone is three plots wide by one plot high, split horizontally into two equal-sized subzones
      // such that there are three plot locations but the middle one sits on the subzone boundary.
      // Only subzone 1 is requested.
      val subzoneWidth = MONITORING_PLOT_SIZE * 1.5
      val subzoneHeight = MONITORING_PLOT_SIZE + 1
      val subzone1Boundary =
          Turtle(siteOrigin).makeMultiPolygon { rectangle(subzoneWidth, subzoneHeight) }
      val subzone2Boundary =
          Turtle(siteOrigin).makeMultiPolygon {
            east(subzoneWidth)
            rectangle(subzoneWidth, subzoneHeight)
          }
      val subzone1PlotBoundary = Turtle(siteOrigin).makePolygon { square(MONITORING_PLOT_SIZE) }
      val bothSubzonesPlotBoundary =
          Turtle(siteOrigin).makePolygon {
            east(MONITORING_PLOT_SIZE)
            square(MONITORING_PLOT_SIZE)
          }
      val subzone2PlotBoundary =
          Turtle(siteOrigin).makePolygon {
            east(MONITORING_PLOT_SIZE * 2)
            square(MONITORING_PLOT_SIZE)
          }

      val model =
          plantingZoneModel(
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
                              ),
                      ),
                      plantingSubzoneModel(
                          boundary = subzone2Boundary,
                          id = 2,
                          plots =
                              listOf(monitoringPlotModel(boundary = subzone2PlotBoundary, id = 20)),
                      ),
                  ),
          )

      val expected = monitoringPlotIds(10)

      repeatTest {
        val chosenIds =
            model.chooseTemporaryPlots(plantingSubzoneIds(1), siteOrigin).map {
              model.findMonitoringPlot(it)?.id
            }

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
                                  temporaryIds = listOf(11, 12, 13, 14),
                              ),
                      ),
                      plantingSubzoneModel(
                          id = 2,
                          plots =
                              monitoringPlotModels(
                                  permanentIds = listOf(20),
                                  temporaryIds = listOf(21, 22, 23, 24),
                              ),
                      ),
                      plantingSubzoneModel(
                          id = 3,
                          plots = monitoringPlotModels(temporaryIds = listOf(30, 31, 32, 33, 34)),
                      ),
                  ),
          )

      val availablePlotIds =
          listOf(
              monitoringPlotIds(11, 12, 13, 14),
              monitoringPlotIds(21, 22, 23, 24),
              monitoringPlotIds(30, 31, 32, 33, 34),
          )

      repeatTest {
        val chosenIds =
            model
                .chooseTemporaryPlots(plantingSubzoneIds(1, 2, 3), siteOrigin)
                .map { model.findMonitoringPlot(it)?.id }
                .toSet()

        val numChosenPerSubzone = availablePlotIds.map { ids -> ids.intersect(chosenIds).size }

        assertEquals(listOf(2, 2, 2), numChosenPerSubzone, "Number of plots chosen in each subzone")
      }
    }

    @Test
    fun `places excess plots in subzones with fewest permanent plots`() {
      val model =
          plantingZoneModel(
              numPermanentPlots = 12,
              numTemporaryPlots = 5,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          id = 1,
                          plots =
                              listOf(
                                  monitoringPlotModel(10, permanentIndex = 1),
                                  monitoringPlotModel(11, permanentIndex = 2),
                                  monitoringPlotModel(12, permanentIndex = 3),
                                  monitoringPlotModel(13, permanentIndex = 4),
                                  monitoringPlotModel(14, permanentIndex = 5),
                                  monitoringPlotModel(15, permanentIndex = 6),
                                  monitoringPlotModel(16, permanentIndex = 7),
                                  monitoringPlotModel(17, permanentIndex = 8),
                                  monitoringPlotModel(18),
                                  monitoringPlotModel(19),
                              ),
                      ),
                      plantingSubzoneModel(
                          id = 2,
                          plots =
                              listOf(
                                  monitoringPlotModel(20, permanentIndex = 9),
                                  monitoringPlotModel(21, permanentIndex = 10),
                                  monitoringPlotModel(22, permanentIndex = 11),
                                  monitoringPlotModel(23, permanentIndex = 12),
                                  monitoringPlotModel(24),
                                  monitoringPlotModel(25),
                              ),
                      ),
                      plantingSubzoneModel(
                          id = 3,
                          plots = monitoringPlotModels(temporaryIds = listOf(30, 31, 32, 33, 34)),
                      ),
                  ),
          )

      val availablePlotIds =
          listOf(
              monitoringPlotIds(18, 19),
              monitoringPlotIds(24, 25),
              monitoringPlotIds(30, 31, 32, 33, 34),
          )

      repeatTest {
        val chosenIds =
            model
                .chooseTemporaryPlots(plantingSubzoneIds(1, 2, 3), siteOrigin)
                .map { model.findMonitoringPlot(it)?.id }
                .toSet()

        val numChosenPerSubzone = availablePlotIds.map { ids -> ids.intersect(chosenIds).size }

        assertEquals(listOf(1, 2, 2), numChosenPerSubzone, "Number of plots chosen in each subzone")
      }
    }

    @Test
    fun `places excess plots in requested subzones if no difference in permanent plots`() {
      val model =
          plantingZoneModel(
              numPermanentPlots = 9,
              numTemporaryPlots = 5,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          id = 1,
                          plots =
                              listOf(
                                  monitoringPlotModel(10, permanentIndex = 1),
                                  monitoringPlotModel(11, permanentIndex = 2),
                                  monitoringPlotModel(12, permanentIndex = 3),
                                  monitoringPlotModel(13, permanentIndex = 4),
                                  monitoringPlotModel(14),
                                  monitoringPlotModel(15),
                              ),
                      ),
                      plantingSubzoneModel(
                          id = 2,
                          plots =
                              listOf(
                                  monitoringPlotModel(20, permanentIndex = 5),
                                  monitoringPlotModel(21, permanentIndex = 6),
                                  monitoringPlotModel(22, permanentIndex = 7),
                                  monitoringPlotModel(23, permanentIndex = 8),
                                  monitoringPlotModel(24),
                                  monitoringPlotModel(25),
                              ),
                      ),
                      plantingSubzoneModel(
                          id = 3,
                          plots = monitoringPlotModels(temporaryIds = listOf(30, 31, 32, 33, 34)),
                      ),
                  ),
          )

      val availablePlotIds =
          listOf(
              monitoringPlotIds(14, 15),
              monitoringPlotIds(24, 25),
              monitoringPlotIds(30, 31, 32, 33, 34),
          )

      repeatTest {
        val chosenIds =
            model
                .chooseTemporaryPlots(plantingSubzoneIds(1, 2), siteOrigin)
                .map { model.findMonitoringPlot(it)?.id }
                .toSet()

        val numChosenPerSubzone = availablePlotIds.map { ids -> ids.intersect(chosenIds).size }

        assertEquals(listOf(2, 1, 0), numChosenPerSubzone, "Number of plots chosen in each subzone")
      }
    }

    @Test
    fun `excludes plots that would have been placed in unrequested subzones`() {
      val model =
          plantingZoneModel(
              numTemporaryPlots = 5,
              subzones =
                  listOf(
                      plantingSubzoneModel(
                          id = 1,
                          plots = monitoringPlotModels(temporaryIds = listOf(10, 11, 12, 13, 14)),
                      ),
                      plantingSubzoneModel(
                          id = 2,
                          plots = monitoringPlotModels(temporaryIds = listOf(20, 21, 22, 23, 24)),
                      ),
                      plantingSubzoneModel(
                          id = 3,
                          plots = monitoringPlotModels(temporaryIds = listOf(30, 31, 32, 33, 34)),
                      ),
                  ),
          )

      val availablePlotIds =
          listOf(
              monitoringPlotIds(10, 11, 12, 13, 14),
              monitoringPlotIds(20, 21, 22, 23, 24),
              monitoringPlotIds(30, 31, 32, 33, 34),
          )

      repeatTest {
        val chosenIds =
            model
                .chooseTemporaryPlots(plantingSubzoneIds(2, 3), siteOrigin)
                .map { model.findMonitoringPlot(it)?.id }
                .toSet()

        val numChosenPerSubzone = availablePlotIds.map { ids -> ids.intersect(chosenIds).size }

        assertEquals(listOf(0, 2, 2), numChosenPerSubzone, "Number of plots chosen in each subzone")
      }
    }

    @Test
    fun `throws exception if no subzones`() {
      val model = plantingZoneModel(numTemporaryPlots = 1, subzones = emptyList())

      assertThrows<IllegalArgumentException> { model.chooseTemporaryPlots(emptySet(), siteOrigin) }
    }

    @Test
    fun `throws exception if subzone has too few remaining plots`() {
      val model =
          plantingZoneModel(
              numTemporaryPlots = 6,
              subzones =
                  listOf(
                      plantingSubzoneModel(plots = monitoringPlotModels(permanentIds = listOf(10)))
                  ),
          )

      assertThrows<PlantingSubzoneFullException> {
        model.chooseTemporaryPlots(plantingSubzoneIds(1), siteOrigin)
      }
    }
  }

  @Nested
  inner class FindUnusedSquare {
    @RepeatedTest(20)
    fun `can find square in non-rectangular planting zone`() {
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
              subzones = listOf(plantingSubzoneModel(boundary = siteBoundary, plots = emptyList())),
          )

      val expected = Turtle(siteOrigin).makePolygon { square(50) }

      val actual = zone.findUnusedSquare(siteOrigin, 50)

      if (!expected.equalsExact(actual, 0.1)) {
        assertEquals(expected, actual)
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
              subzones = listOf(plantingSubzoneModel(boundary = siteBoundary, plots = emptyList())),
          )

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
              "Approximate count expected for coordinate $coordinate (origin ${siteOrigin.coordinate})",
          )
        }
      }
    }

    @RepeatedTest(20, failureThreshold = 1)
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
            east(Random.nextInt(edgeMeters - 61))
            north(Random.nextInt(edgeMeters - 61))
            square(61)
          }

      val siteBoundary = geometryFactory.createMultiPolygon((triangles + targetArea).toTypedArray())
      val zone =
          plantingZoneModel(
              boundary = siteBoundary,
              subzones = listOf(plantingSubzoneModel(boundary = siteBoundary, plots = emptyList())),
          )

      val square = zone.findUnusedSquare(siteOrigin, 30)
      assertNotNull(square, "Unused square")

      assertThat(square!!.intersection(targetArea).area)
          .describedAs(
              "Area of the part of the square that is inside the only region the square should " +
                  "fit inside"
          )
          .isGreaterThanOrEqualTo(square.area * 0.999)
    }
  }

  @Nested
  inner class FindUnusedSquares {
    @Test
    fun `excludes permanent monitoring plots`() {
      // Boundary is a 61x31m square, and there is an existing plot in the southwestern 30x30m.
      val siteBoundary = Turtle(siteOrigin).makeMultiPolygon { rectangle(61, 31) }
      val existingPlotPolygon = Turtle(siteOrigin).makePolygon { square(30) }

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
                                      boundary = existingPlotPolygon,
                                      permanentIndex = 1,
                                  )
                              ),
                      )
                  ),
          )

      val expected =
          Turtle(siteOrigin).makePolygon {
            east(30)
            square(30)
          }

      repeat(20) {
        val actual =
            zone.findUnusedSquares(
                gridOrigin = siteOrigin,
                sizeMeters = 30,
                count = 1,
                excludeAllPermanentPlots = true,
            )

        assertEquals(1, actual.size, "Number of squares found")

        if (!expected.equalsExact(actual[0], 0.000001)) {
          assertEquals(expected, actual[0])
        }
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
      east(subzoneId * MONITORING_PLOT_SIZE * 2)
      north(MONITORING_PLOT_SIZE * (plotNumber / 2))
      if (plotNumber.rem(2) == 1) {
        east(MONITORING_PLOT_SIZE)
      }

      square(MONITORING_PLOT_SIZE)
    }
  }

  private fun monitoringPlotModel(
      id: Int = 1,
      boundary: Polygon = monitoringPlotBoundary(id),
      elevationMeters: BigDecimal? = null,
      isAvailable: Boolean = true,
      permanentIndex: Int? = null,
  ): MonitoringPlotModel {
    return MonitoringPlotModel(
        boundary = boundary,
        elevationMeters = elevationMeters,
        id = MonitoringPlotId(id.toLong()),
        isAdHoc = false,
        isAvailable = isAvailable,
        permanentIndex = permanentIndex,
        plotNumber = id.toLong(),
        sizeMeters = MONITORING_PLOT_SIZE_INT,
    )
  }

  private fun monitoringPlotIds(vararg id: Int) = id.map { MonitoringPlotId(it.toLong()) }.toSet()

  private fun monitoringPlotModels(
      permanentIds: List<Int> = emptyList(),
      temporaryIds: List<Int> = emptyList(),
  ): List<MonitoringPlotModel> {
    return permanentIds.map { monitoringPlotModel(id = it, permanentIndex = 1) } +
        temporaryIds.map { monitoringPlotModel(id = it, permanentIndex = null) }
  }

  /**
   * Returns the boundary for a sample subzone. Subzones are arranged in a row from west to east and
   * each one has room for 5 monitoring plots in the layout defined by [monitoringPlotBoundary],
   * plus a 1-meter margin to account for floating-point inaccuracy.
   */
  private fun plantingSubzoneBoundary(id: Int, numPlots: Int): MultiPolygon {
    return Turtle(siteOrigin).makeMultiPolygon {
      east(id * MONITORING_PLOT_SIZE * 2)

      val southwest = currentPosition

      // Figure out the northwest corner's location.
      north(((numPlots + 1) / 2) * MONITORING_PLOT_SIZE)
      val northwest = currentPosition

      moveTo(southwest)

      startDrawing()
      east(MONITORING_PLOT_SIZE * 2)
      north((numPlots / 2) * MONITORING_PLOT_SIZE)

      if (numPlots.and(1) == 0) {
        // Even number of plots; the boundary is a plain rectangle.
        moveTo(northwest)
      } else {
        // Odd number of plots; space for the last plot is on the west half of the northern edge.
        west(MONITORING_PLOT_SIZE)
        north(MONITORING_PLOT_SIZE)
        moveTo(northwest)
      }
    }
  }

  private fun plantingSubzoneModel(
      id: Int = 1,
      plots: List<MonitoringPlotModel> = emptyList(),
      boundary: MultiPolygon = plantingSubzoneBoundary(id, plots.size),
  ) =
      PlantingSubzoneModel(
          areaHa = BigDecimal.ONE,
          boundary = boundary,
          id = PlantingSubzoneId(id.toLong()),
          fullName = "name",
          name = "name",
          plantingCompletedTime = null,
          monitoringPlots = plots,
          stableId = StableId("name"),
      )

  private fun plantingSubzoneIds(vararg id: Int) = id.map { PlantingSubzoneId(it.toLong()) }.toSet()

  /**
   * Returns the boundary for a sample planting zone that contains some number of 51x76 meter
   * subzones laid out west to east.
   */
  private fun plantingZoneBoundary(subzones: List<ExistingPlantingSubzoneModel>): MultiPolygon {
    if (subzones.isEmpty()) {
      return multiPolygon(1)
    }

    return subzones
        .map { it.boundary }
        .reduce { acc: Geometry, subzone: Geometry -> acc.union(subzone) }
        .toMultiPolygon()
  }

  private fun plantingZoneModel(
      numTemporaryPlots: Int = 1,
      numPermanentPlots: Int = 1,
      subzones: List<ExistingPlantingSubzoneModel>,
      boundary: MultiPolygon = plantingZoneBoundary(subzones),
  ) =
      ExistingPlantingZoneModel(
          areaHa = BigDecimal.ONE,
          boundary = boundary,
          boundaryModifiedTime = Instant.EPOCH,
          errorMargin = BigDecimal.ONE,
          id = PlantingZoneId(1),
          name = "name",
          numPermanentPlots = numPermanentPlots,
          numTemporaryPlots = numTemporaryPlots,
          plantingSubzones = subzones,
          stableId = StableId("name"),
          studentsT = BigDecimal.ONE,
          targetPlantingDensity = BigDecimal.ONE,
          variance = BigDecimal.ONE,
      )
}
