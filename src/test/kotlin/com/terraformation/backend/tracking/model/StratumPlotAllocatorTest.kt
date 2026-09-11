package com.terraformation.backend.tracking.model

import com.terraformation.backend.db.tracking.MonitoringPlotId
import com.terraformation.backend.util.toMultiPolygon
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.locationtech.jts.geom.Envelope

class StratumPlotAllocatorTest : BaseStratumModelTest() {
  private fun fullStratum(numPlots: Int = 4, numPermanentPlots: Int = 6): ExistingStratumModel =
      stratumModel(
          numPermanentPlots = numPermanentPlots,
          numTemporaryPlots = 2,
          substrata =
              listOf(
                  substratumModel(
                      plots =
                          List(numPlots) { monitoringPlotModel(10 + it, permanentIndex = it + 1) }
                  )
              ),
      )

  @Test
  fun `uses StratumModel allocator when there is room for all the plots`() {
    val stratum =
        stratumModel(
            numPermanentPlots = 1,
            numTemporaryPlots = 1,
            substrata =
                listOf(
                    substratumModel(
                        id = 1,
                        plots = listOf(monitoringPlotModel(10, permanentIndex = 1)),
                        boundary = substratumBoundary(1, 2),
                    )
                ),
        )

    val allocation =
        StratumPlotAllocator(
                stratum,
                siteOrigin,
                exclusion = null,
            )
            .allocate(substrataIds(1))

    assertEquals(2, allocation.numConfigured, "Configured plots")
    assertEquals(2, allocation.numAllocated, "Allocated plots")
    assertFalse(allocation.isShortfall, "Should not be a shortfall")
    assertEquals(setOf(MonitoringPlotId(10)), allocation.permanentPlotIds, "Permanent plots")
    assertEquals(1, allocation.temporaryPlotBoundaries.size, "Number of temporary plots")
  }

  @ParameterizedTest
  @CsvSource("2,6,2", "4,6,3", "6,8,5")
  fun `tries to allocate 75 percent of plots as permanent`(
      capacity: Int,
      configuredPermanent: Int,
      expectedPermanent: Int,
  ) {
    val stratum = fullStratum(capacity, configuredPermanent)

    val allocation =
        StratumPlotAllocator(stratum, siteOrigin, exclusion = null).allocate(substrataIds(1))

    assertEquals(configuredPermanent + 2, allocation.numConfigured, "Configured plots")
    assertEquals(capacity, allocation.numAllocated, "Allocated plots")
    assertTrue(allocation.isShortfall, "Should be a shortfall")
    assertEquals(
        (0..<expectedPermanent).map { MonitoringPlotId(it.toLong() + 10) }.toSet(),
        allocation.permanentPlotIds,
        "Should keep the lowest permanent indexes",
    )
    assertEquals(
        capacity - expectedPermanent,
        allocation.temporaryPlotBoundaries.size,
        "Number of temporary plots",
    )
  }

  @Test
  fun `does not turn permanent plots that cross substratum boundaries into temporary plots`() {
    val boundary = substratumBoundary(1, 4)
    val envelope = boundary.envelopeInternal
    val splitX = envelope.minX + envelope.width / 4
    val westBoundary =
        boundary
            .intersection(
                geometryFactory.toGeometry(
                    Envelope(envelope.minX, splitX, envelope.minY, envelope.maxY)
                )
            )
            .toMultiPolygon()
    val eastBoundary = boundary.difference(westBoundary).toMultiPolygon()

    // Plots 10 and 12 cross the substratum boundary; plots 11 and 13 fit within the east
    // substratum. Plot 12 has the highest permanent index, making it a candidate for demotion.
    val stratum =
        stratumModel(
            numPermanentPlots = 6,
            numTemporaryPlots = 2,
            boundary = boundary,
            substrata =
                listOf(
                    substratumModel(id = 1, boundary = westBoundary),
                    substratumModel(
                        id = 2,
                        boundary = eastBoundary,
                        plots =
                            listOf(
                                monitoringPlotModel(10, permanentIndex = 1),
                                monitoringPlotModel(11, permanentIndex = 2),
                                monitoringPlotModel(13, permanentIndex = 3),
                                monitoringPlotModel(12, permanentIndex = 4),
                            ),
                    ),
                ),
        )

    val requestedSubstratumIds = substrataIds(1, 2)
    val allocation =
        StratumPlotAllocator(stratum, siteOrigin, exclusion = null).allocate(requestedSubstratumIds)

    assertEquals(8, allocation.numConfigured, "Configured plots")
    assertEquals(3, allocation.numAllocated, "Allocated count should span the whole stratum")
    assertTrue(allocation.isShortfall, "Should be a shortfall")
    assertEquals(
        monitoringPlotIds(10, 11),
        allocation.permanentPlotIds,
        "Should keep the lowest permanent indexes at the reduced capacity",
    )
    assertEquals(1, allocation.temporaryPlotBoundaries.size, "Number of temporary plots")
  }

  @Test
  fun `does not allocate permanent plots with indexes greater than numPermanentPlots`() {
    val stratum =
        stratumModel(
            numPermanentPlots = 1,
            numTemporaryPlots = 7,
            substrata =
                listOf(
                    substratumModel(
                        id = 1,
                        plots =
                            listOf(
                                monitoringPlotModel(10, permanentIndex = 1),
                                // These might be plots from an older version of the stratum when
                                // numPermanentPlots was higher.
                                monitoringPlotModel(11, permanentIndex = 2),
                                monitoringPlotModel(12, permanentIndex = 3),
                                monitoringPlotModel(13, permanentIndex = 4),
                            ),
                        boundary = substratumBoundary(1, 4),
                    )
                ),
        )

    val allocation =
        StratumPlotAllocator(
                stratum,
                siteOrigin,
                exclusion = null,
            )
            .allocate(substrataIds(1))

    assertEquals(4, allocation.numAllocated, "Allocated plots")
    assertEquals(setOf(MonitoringPlotId(10)), allocation.permanentPlotIds, "Permanent plots")
    assertEquals(3, allocation.temporaryPlotBoundaries.size, "Temporary plots")
  }

  @Test
  fun `throws exception if the stratum has no room for any plots at all`() {
    val stratum =
        stratumModel(
            numPermanentPlots = 1,
            numTemporaryPlots = 0,
            substrata =
                listOf(
                    substratumModel(
                        id = 1,
                        plots = listOf(monitoringPlotModel(10, isAvailable = false)),
                        boundary = substratumBoundary(1, 1),
                    )
                ),
        )

    assertThrows<StratumFullException> {
      StratumPlotAllocator(
              stratum,
              siteOrigin,
              exclusion = null,
          )
          .allocate(substrataIds(1))
    }
  }

  @Test
  fun `only returns plots in requested substrata`() {
    val stratum =
        stratumModel(
            numPermanentPlots = 6,
            numTemporaryPlots = 2,
            substrata =
                listOf(
                    substratumModel(
                        id = 1,
                        plots =
                            listOf(
                                monitoringPlotModel(10, permanentIndex = 1),
                                monitoringPlotModel(11, permanentIndex = 2),
                            ),
                        boundary = substratumBoundary(1, 2),
                    ),
                    substratumModel(
                        id = 2,
                        plots =
                            listOf(
                                monitoringPlotModel(20, permanentIndex = 3),
                                monitoringPlotModel(21, permanentIndex = 4),
                            ),
                        boundary = substratumBoundary(2, 2),
                    ),
                ),
        )

    val allocation =
        StratumPlotAllocator(
                stratum,
                siteOrigin,
                exclusion = null,
            )
            .allocate(substrataIds(1))

    assertEquals(4, allocation.numAllocated, "Allocated count should span the whole stratum")
    assertEquals(
        setOf(MonitoringPlotId(10), MonitoringPlotId(11)),
        allocation.permanentPlotIds,
        "Only plots in the requested substratum should be returned",
    )
  }
}
