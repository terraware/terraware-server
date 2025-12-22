package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.StableId
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.model.ExistingPlantingSeasonModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingStratumModel
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.StratumModel
import com.terraformation.backend.tracking.model.SubstratumModel
import com.terraformation.backend.util.toInstant
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Geometry

internal class PlantingSiteStoreFetchSiteTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class FetchSiteById {
    @Test
    fun `honors depth`() {
      val plantingSiteId =
          insertPlantingSite(
              boundary = multiPolygon(3.0),
              countryCode = "MX",
              exclusion = multiPolygon(1.5),
              gridOrigin = point(9, 10),
              timeZone = timeZone,
          )
      val boundaryModifiedTime = Instant.ofEpochSecond(5001)
      val stratumId =
          insertStratum(
              boundary = multiPolygon(2.0),
              boundaryModifiedTime = boundaryModifiedTime,
              targetPlantingDensity = BigDecimal.ONE,
          )
      val substratumId = insertSubstratum(boundary = multiPolygon(1.0))
      val monitoringPlotId =
          insertMonitoringPlot(boundary = polygon(0.1), elevationMeters = BigDecimal.TEN)
      insertMonitoringPlot(boundary = polygon(0.1), isAdHoc = true)

      val season1StartDate = LocalDate.of(2023, 6, 1)
      val season1EndDate = LocalDate.of(2023, 7, 31)
      val plantingSeasonId1 =
          insertPlantingSeason(
              startDate = season1StartDate,
              endDate = season1EndDate,
              timeZone = timeZone,
          )
      val season2StartDate = LocalDate.of(2023, 1, 1)
      val season2EndDate = LocalDate.of(2023, 1, 31)
      val plantingSeasonId2 =
          insertPlantingSeason(
              startDate = season2StartDate,
              endDate = season2EndDate,
              timeZone = timeZone,
          )

      val adHocPlotId =
          insertMonitoringPlot(
              boundary = polygon(0.4),
              substratumId = null,
              isAdHoc = true,
              isAvailable = false,
          )
      val exteriorPlotId = insertMonitoringPlot(boundary = polygon(0.2), substratumId = null)

      val expectedWithSite =
          ExistingPlantingSiteModel(
              boundary = multiPolygon(3),
              countryCode = "MX",
              description = null,
              exclusion = multiPolygon(1.5),
              gridOrigin = point(9, 10),
              id = plantingSiteId,
              name = "Site 1",
              organizationId = organizationId,
              plantingSeasons =
                  listOf(
                      ExistingPlantingSeasonModel(
                          endDate = season2EndDate,
                          endTime = season2EndDate.plusDays(1).toInstant(timeZone),
                          id = plantingSeasonId2,
                          isActive = false,
                          startDate = season2StartDate,
                          startTime = season2StartDate.toInstant(timeZone),
                      ),
                      ExistingPlantingSeasonModel(
                          endDate = season1EndDate,
                          endTime = season1EndDate.plusDays(1).toInstant(timeZone),
                          id = plantingSeasonId1,
                          isActive = false,
                          startDate = season1StartDate,
                          startTime = season1StartDate.toInstant(timeZone),
                      ),
                  ),
              strata = emptyList(),
              timeZone = timeZone,
          )

      val expectedWithStratum =
          expectedWithSite.copy(
              strata =
                  listOf(
                      ExistingStratumModel(
                          areaHa = BigDecimal.TEN,
                          boundary = multiPolygon(2.0),
                          boundaryModifiedTime = boundaryModifiedTime,
                          id = stratumId,
                          name = "S1",
                          substrata = emptyList(),
                          stableId = StableId("S1"),
                          targetPlantingDensity = BigDecimal.ONE,
                      ),
                  )
          )

      val expectedWithSubstratum =
          expectedWithStratum.copy(
              strata =
                  listOf(
                      expectedWithStratum.strata[0].copy(
                          substrata =
                              listOf(
                                  SubstratumModel(
                                      areaHa = BigDecimal.ONE,
                                      boundary = multiPolygon(1.0),
                                      id = substratumId,
                                      fullName = "S1-1",
                                      name = "1",
                                      plantingCompletedTime = null,
                                      stableId = StableId("S1-1"),
                                      monitoringPlots = emptyList(),
                                  )
                              )
                      )
                  ),
          )

      val expectedWithPlot =
          expectedWithSubstratum.copy(
              adHocPlots =
                  listOf(
                      MonitoringPlotModel(
                          boundary = polygon(0.4),
                          elevationMeters = null,
                          id = adHocPlotId,
                          isAdHoc = true,
                          isAvailable = false,
                          plotNumber = 3,
                          sizeMeters = 30,
                      )
                  ),
              exteriorPlots =
                  listOf(
                      MonitoringPlotModel(
                          boundary = polygon(0.2),
                          elevationMeters = null,
                          id = exteriorPlotId,
                          isAdHoc = false,
                          isAvailable = true,
                          plotNumber = 4,
                          sizeMeters = 30,
                      )
                  ),
              strata =
                  listOf(
                      expectedWithSubstratum.strata[0].copy(
                          substrata =
                              listOf(
                                  expectedWithSubstratum.strata[0]
                                      .substrata[0]
                                      .copy(
                                          monitoringPlots =
                                              listOf(
                                                  MonitoringPlotModel(
                                                      boundary = polygon(0.1),
                                                      elevationMeters = BigDecimal.TEN,
                                                      id = monitoringPlotId,
                                                      isAdHoc = false,
                                                      isAvailable = true,
                                                      plotNumber = 1,
                                                      sizeMeters = 30,
                                                  )
                                              ),
                                      )
                              )
                      )
                  ),
          )

      val allExpected =
          mapOf(
              PlantingSiteDepth.Site to expectedWithSite,
              PlantingSiteDepth.Stratum to expectedWithStratum,
              PlantingSiteDepth.Substratum to expectedWithSubstratum,
              PlantingSiteDepth.Plot to expectedWithPlot,
          )

      val allActual =
          PlantingSiteDepth.entries.associateWith { store.fetchSiteById(plantingSiteId, it) }

      if (!allExpected.all { (depth, expected) -> allActual[depth]!!.equals(expected, 0.00001) }) {
        assertEquals(allExpected, allActual)
      }
    }

    @Test
    fun `includes latest observation details`() {
      val plantingSiteId =
          insertPlantingSite(
              boundary = multiPolygon(3.0),
              countryCode = "US",
              timeZone = timeZone,
          )
      val boundaryModifiedTime = Instant.ofEpochSecond(5001)
      val stratumId1 =
          insertStratum(
              boundary = multiPolygon(2.0),
              boundaryModifiedTime = boundaryModifiedTime,
              targetPlantingDensity = BigDecimal.ONE,
          )
      val substratumId11 = insertSubstratum(boundary = multiPolygon(1.0))
      val monitoringPlotId111 = insertMonitoringPlot(boundary = polygon(0.1))
      val monitoringPlotId112 = insertMonitoringPlot(boundary = polygon(0.1))

      val substratumId12 = insertSubstratum(boundary = multiPolygon(1.0))
      val monitoringPlotId121 = insertMonitoringPlot(boundary = polygon(0.1))
      val monitoringPlotId122 = insertMonitoringPlot(boundary = polygon(0.1))

      val stratumId2 =
          insertStratum(
              boundary = multiPolygon(2.0),
              boundaryModifiedTime = boundaryModifiedTime,
              targetPlantingDensity = BigDecimal.ONE,
          )
      val substratumId2 =
          insertSubstratum(boundary = multiPolygon(1.0), fullName = "S2-1", name = "1")
      val monitoringPlotId2 = insertMonitoringPlot(boundary = polygon(0.1))

      val plotHistoryIds =
          monitoringPlotHistoriesDao.findAll().associate { it.monitoringPlotId to it.id }

      val observationId1 =
          insertObservation(
              completedTime = Instant.ofEpochSecond(1000),
          )
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.ofEpochSecond(999),
          completedBy = user.userId,
          completedTime = Instant.ofEpochSecond(1000),
          isPermanent = true,
          monitoringPlotId = monitoringPlotId111,
          monitoringPlotHistoryId = plotHistoryIds[monitoringPlotId111]!!,
      )
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.ofEpochSecond(999),
          completedBy = user.userId,
          completedTime = Instant.ofEpochSecond(1000),
          isPermanent = true,
          monitoringPlotId = monitoringPlotId121,
          monitoringPlotHistoryId = plotHistoryIds[monitoringPlotId121]!!,
      )
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.ofEpochSecond(999),
          completedBy = user.userId,
          completedTime = Instant.ofEpochSecond(1000),
          isPermanent = true,
          monitoringPlotId = monitoringPlotId122,
          monitoringPlotHistoryId = plotHistoryIds[monitoringPlotId122]!!,
      )

      val observationId2 =
          insertObservation(
              completedTime = Instant.ofEpochSecond(2000),
          )
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.ofEpochSecond(1999),
          completedBy = user.userId,
          completedTime = Instant.ofEpochSecond(2000),
          isPermanent = true,
          monitoringPlotId = monitoringPlotId122,
          monitoringPlotHistoryId = plotHistoryIds[monitoringPlotId122]!!,
      )
      insertObservationPlot(
          claimedBy = user.userId,
          claimedTime = Instant.ofEpochSecond(1999),
          completedBy = user.userId,
          completedTime = Instant.ofEpochSecond(2000),
          isPermanent = true,
          monitoringPlotId = monitoringPlotId2,
          monitoringPlotHistoryId = plotHistoryIds[monitoringPlotId2]!!,
      )

      /* (x) - for latest (o) for observed but not the latest.

        Observations         | 1 | 2 |
        ---------------------|---|---|
        Planting Site        | o | x |
        | - Stratum 1        | o | x |
        | - - Substratum 1-1 | x |   |
        | - - - Plot 1-1-1   | x |   |
        | - - - Plot 1-1-2   |   |   |
        | - - Substratum 1-2 | o | x |
        | - - - Plot 1-2-1   | x |   |
        | - - - Plot 1-2-2   | o | x |
        | - Stratum 2        |   | x |
        | - - Substratum 1-2 |   | x |
        | - - - Plot 1-2-1   |   | x |
      */

      val expected =
          ExistingPlantingSiteModel(
              boundary = multiPolygon(3),
              countryCode = "US",
              description = null,
              exclusion = null,
              gridOrigin = null,
              id = plantingSiteId,
              latestObservationCompletedTime = Instant.ofEpochSecond(2000),
              latestObservationId = observationId2,
              name = "Site 1",
              organizationId = organizationId,
              plantingSeasons = emptyList(),
              strata =
                  listOf(
                      ExistingStratumModel(
                          areaHa = BigDecimal.TEN,
                          boundary = multiPolygon(2.0),
                          boundaryModifiedTime = boundaryModifiedTime,
                          id = stratumId1,
                          latestObservationCompletedTime = Instant.ofEpochSecond(2000),
                          latestObservationId = observationId2,
                          name = "S1",
                          substrata =
                              listOf(
                                  SubstratumModel(
                                      areaHa = BigDecimal.ONE,
                                      boundary = multiPolygon(1.0),
                                      id = substratumId11,
                                      latestObservationCompletedTime = Instant.ofEpochSecond(1000),
                                      latestObservationId = observationId1,
                                      fullName = "S1-1",
                                      name = "1",
                                      plantingCompletedTime = null,
                                      stableId = StableId("S1-1"),
                                      monitoringPlots =
                                          listOf(
                                              MonitoringPlotModel(
                                                  boundary = polygon(0.1),
                                                  elevationMeters = null,
                                                  id = monitoringPlotId111,
                                                  isAdHoc = false,
                                                  isAvailable = true,
                                                  plotNumber = 1,
                                                  sizeMeters = 30,
                                              ),
                                              MonitoringPlotModel(
                                                  boundary = polygon(0.1),
                                                  elevationMeters = null,
                                                  id = monitoringPlotId112,
                                                  isAdHoc = false,
                                                  isAvailable = true,
                                                  plotNumber = 2,
                                                  sizeMeters = 30,
                                              ),
                                          ),
                                  ),
                                  SubstratumModel(
                                      areaHa = BigDecimal.ONE,
                                      boundary = multiPolygon(1.0),
                                      id = substratumId12,
                                      latestObservationCompletedTime = Instant.ofEpochSecond(2000),
                                      latestObservationId = observationId2,
                                      fullName = "S1-2",
                                      name = "2",
                                      plantingCompletedTime = null,
                                      stableId = StableId("S1-2"),
                                      monitoringPlots =
                                          listOf(
                                              MonitoringPlotModel(
                                                  boundary = polygon(0.1),
                                                  elevationMeters = null,
                                                  id = monitoringPlotId121,
                                                  isAdHoc = false,
                                                  isAvailable = true,
                                                  plotNumber = 3,
                                                  sizeMeters = 30,
                                              ),
                                              MonitoringPlotModel(
                                                  boundary = polygon(0.1),
                                                  elevationMeters = null,
                                                  id = monitoringPlotId122,
                                                  isAdHoc = false,
                                                  isAvailable = true,
                                                  plotNumber = 4,
                                                  sizeMeters = 30,
                                              ),
                                          ),
                                  ),
                              ),
                          stableId = StableId("S1"),
                          targetPlantingDensity = BigDecimal.ONE,
                      ),
                      ExistingStratumModel(
                          areaHa = BigDecimal.TEN,
                          boundary = multiPolygon(2.0),
                          boundaryModifiedTime = boundaryModifiedTime,
                          id = stratumId2,
                          latestObservationCompletedTime = Instant.ofEpochSecond(2000),
                          latestObservationId = observationId2,
                          name = "S2",
                          substrata =
                              listOf(
                                  SubstratumModel(
                                      areaHa = BigDecimal.ONE,
                                      boundary = multiPolygon(1.0),
                                      id = substratumId2,
                                      latestObservationCompletedTime = Instant.ofEpochSecond(2000),
                                      latestObservationId = observationId2,
                                      fullName = "S2-1",
                                      name = "1",
                                      plantingCompletedTime = null,
                                      stableId = StableId("S2-1"),
                                      monitoringPlots =
                                          listOf(
                                              MonitoringPlotModel(
                                                  boundary = polygon(0.1),
                                                  elevationMeters = null,
                                                  id = monitoringPlotId2,
                                                  isAdHoc = false,
                                                  isAvailable = true,
                                                  plotNumber = 5,
                                                  sizeMeters = 30,
                                              ),
                                          ),
                                  ),
                              ),
                          stableId = StableId("S2"),
                          targetPlantingDensity = BigDecimal.ONE,
                      ),
                  ),
              timeZone = timeZone,
          )

      assertEquals(expected, store.fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot))
    }

    @Test
    fun `transforms coordinates to SRID 4326`() {
      val crsFactory = CRS.getAuthorityFactory(true)
      val crs3857 = crsFactory.createCoordinateReferenceSystem("EPSG:3857")
      val crs4326 = crsFactory.createCoordinateReferenceSystem("EPSG:4326")
      val transform4326To3857 = CRS.findMathTransform(crs4326, crs3857)

      @Suppress("UNCHECKED_CAST")
      fun <T : Geometry> T.to3857(): T {
        return JTS.transform(this, transform4326To3857).also { it.srid = 3857 } as T
      }

      val siteBoundary4326 = multiPolygon(30)
      val stratumBoundary4326 = multiPolygon(20)
      val substratumBoundary4326 = multiPolygon(10)
      val monitoringPlotBoundary4326 = polygon(1)
      val exclusion4326 = multiPolygon(5)

      val siteBoundary3857 = siteBoundary4326.to3857()
      val stratumBoundary3857 = stratumBoundary4326.to3857()
      val substratumBoundary3857 = substratumBoundary4326.to3857()
      val monitoringPlotBoundary3857 = monitoringPlotBoundary4326.to3857()
      val exclusion3857 = exclusion4326.to3857()

      val plantingSiteId =
          insertPlantingSite(boundary = siteBoundary3857, exclusion = exclusion3857)
      val stratumId = insertStratum(boundary = stratumBoundary3857)
      val substratumId = insertSubstratum(boundary = substratumBoundary3857)
      val monitoringPlotId = insertMonitoringPlot(boundary = monitoringPlotBoundary3857)

      val expected =
          ExistingPlantingSiteModel(
              boundary = siteBoundary4326,
              description = null,
              exclusion = exclusion4326,
              id = plantingSiteId,
              name = "Site 1",
              organizationId = organizationId,
              strata =
                  listOf(
                      StratumModel(
                          areaHa = BigDecimal.TEN,
                          boundary = stratumBoundary4326,
                          boundaryModifiedTime = Instant.EPOCH,
                          id = stratumId,
                          name = "S1",
                          stableId = StableId("S1"),
                          substrata =
                              listOf(
                                  SubstratumModel(
                                      areaHa = BigDecimal.ONE,
                                      boundary = substratumBoundary4326,
                                      id = substratumId,
                                      fullName = "S1-1",
                                      name = "1",
                                      plantingCompletedTime = null,
                                      stableId = StableId("S1-1"),
                                      monitoringPlots =
                                          listOf(
                                              MonitoringPlotModel(
                                                  boundary = monitoringPlotBoundary4326,
                                                  elevationMeters = null,
                                                  id = monitoringPlotId,
                                                  isAdHoc = false,
                                                  isAvailable = true,
                                                  plotNumber = 1,
                                                  sizeMeters = 30,
                                              )
                                          ),
                                  )
                              ),
                      )
                  ),
          )

      val actual = store.fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)

      if (!expected.equals(actual, 0.000001)) {
        assertEquals(expected, actual)
      }
    }

    @Test
    fun `throws exception if no permission to read planting site`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canReadPlantingSite(any()) } returns false

      assertThrows<PlantingSiteNotFoundException> {
        store.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
      }
    }
  }
}
