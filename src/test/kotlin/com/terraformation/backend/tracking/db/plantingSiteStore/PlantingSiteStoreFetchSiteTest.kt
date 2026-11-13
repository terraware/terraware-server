package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.ObservationPlotStatus
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.ObservationType
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.ExistingStratumModel
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.StratumModel
import com.terraformation.backend.tracking.model.SubstratumModel
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.locationtech.jts.geom.Geometry

internal class PlantingSiteStoreFetchSiteTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class FetchSiteById {
    @ParameterizedTest
    @EnumSource(ObservationState::class, names = ["Upcoming", "InProgress", "Overdue"])
    fun `honors depth`(baselineState: ObservationState) {
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
              initialPlantingDensity = BigDecimal.ONE,
          )
      val substratumId = insertSubstratum(boundary = multiPolygon(1.0))
      val monitoringPlotId =
          insertMonitoringPlot(boundary = polygon(0.1), elevationMeters = BigDecimal.TEN)
      insertMonitoringPlot(boundary = polygon(0.1), isAdHoc = true)

      val adHocPlotId =
          insertMonitoringPlot(
              boundary = polygon(0.4),
              substratumId = null,
              isAdHoc = true,
              isAvailable = false,
          )
      val exteriorPlotId = insertMonitoringPlot(boundary = polygon(0.2), substratumId = null)
      val baselineId =
          insertObservation(observationType = ObservationType.BaselineSurvey, state = baselineState)
      insertObservationRequestedSubstratum(substratumId = substratumId)

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
                          initialPlantingDensity = BigDecimal.ONE,
                          name = "S1",
                          substrata = emptyList(),
                          stableId = StableId("S1"),
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
                                      latestBaselineSurveyDate = LocalDate.of(2023, 1, 1),
                                      latestBaselineSurveyId = baselineId,
                                      latestBaselineSurveyStatus = baselineState,
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

      allExpected.forEach { (depth, expected) ->
        assertEquals(
            expected.strata
                .flatMap { it.substrata }
                .map {
                  Triple(
                      it.latestBaselineSurveyDate,
                      it.latestBaselineSurveyId,
                      it.latestBaselineSurveyStatus,
                  )
                },
            allActual[depth]!!
                .strata
                .flatMap { it.substrata }
                .map {
                  Triple(
                      it.latestBaselineSurveyDate,
                      it.latestBaselineSurveyId,
                      it.latestBaselineSurveyStatus,
                  )
                },
            "Latest Baseline Survey fields at depth $depth",
        )
      }

      if (!allExpected.all { (depth, expected) -> allActual[depth]!!.equals(expected, 0.00001) }) {
        assertEquals(allExpected, allActual)
      }
    }

    @ParameterizedTest
    @EnumSource(ObservationState::class, names = ["Completed", "Abandoned"])
    fun `returns simplified site`(baselineState: ObservationState) {
      val siteBoundary = multiPolygon(20.0)
      val exclusion = multiPolygon(10.0)
      val stratumBoundary = multiPolygon(15.0)
      val substratumBoundary = multiPolygon(12.0)

      val simplifiedSiteBoundary = multiPolygon(2.0)
      val simplifiedExclusion = multiPolygon(1.0)
      val simplifiedStratumBoundary = multiPolygon(1.5)
      val simplifiedSubstratumBoundary = multiPolygon(1.2)

      val siteId = insertPlantingSite(boundary = siteBoundary, exclusion = exclusion)
      insertSimplifiedPlantingSite(
          boundary = simplifiedSiteBoundary,
          exclusion = simplifiedExclusion,
      )
      insertStratum(boundary = stratumBoundary)
      insertSimplifiedStratum(boundary = simplifiedStratumBoundary)
      val originalSubstratumId = insertSubstratum(boundary = substratumBoundary)
      insertSimplifiedSubstratum(boundary = simplifiedSubstratumBoundary)
      val plotId = insertMonitoringPlot()
      val movedToSubstratumId = insertSubstratum()
      val baselineId =
          insertObservation(
              observationType = ObservationType.BaselineSurvey,
              state = baselineState,
              completedTime = Instant.parse("2023-01-03T00:00:00Z"),
          )
      insertObservationPlot(
          monitoringPlotId = plotId,
          completedBy = user.userId,
          completedTime = Instant.parse("2023-01-02T05:00:00Z"),
      )
      monitoringPlotsDao.update(
          monitoringPlotsDao.fetchOneById(plotId)!!.copy(substratumId = movedToSubstratumId)
      )

      val site = store.fetchSiteById(siteId, PlantingSiteDepth.Substratum, true)
      val original = site.strata.single().substrata.single { it.id == originalSubstratumId }
      assertEquals(
          Triple(LocalDate.of(2023, 1, 2), baselineId, baselineState),
          Triple(
              original.latestBaselineSurveyDate,
              original.latestBaselineSurveyId,
              original.latestBaselineSurveyStatus,
          ),
          "Latest Baseline Survey stays with its historical substratum",
      )
      val substratum = site.strata.single().substrata.single { it.id == movedToSubstratumId }
      assertEquals(
          Triple(null, null, null),
          Triple(
              substratum.latestBaselineSurveyDate,
              substratum.latestBaselineSurveyId,
              substratum.latestBaselineSurveyStatus,
          ),
          "Latest Baseline Survey does not move to the plot's current substratum",
      )
      assertGeometryEquals(simplifiedSiteBoundary, site.boundary, "Site Boundary")
      assertGeometryEquals(simplifiedExclusion, site.exclusion, "Site Exclusion")
      assertGeometryEquals(simplifiedStratumBoundary, site.strata[0].boundary, "Stratum Boundary")
      assertGeometryEquals(
          simplifiedSubstratumBoundary,
          site.strata[0].substrata[0].boundary,
          "Substratum Boundary",
      )
    }

    @Test
    fun `fall back to full site if simplified site does not exist`() {
      val siteBoundary = multiPolygon(20.0)
      val exclusion = multiPolygon(10.0)
      val stratumBoundary = multiPolygon(15.0)
      val substratumBoundary = multiPolygon(12.0)

      val siteId = insertPlantingSite(boundary = siteBoundary, exclusion = exclusion)
      insertStratum(boundary = stratumBoundary)
      insertSubstratum(boundary = substratumBoundary)

      val site = store.fetchSiteById(siteId, PlantingSiteDepth.Substratum, true)
      assertGeometryEquals(siteBoundary, site.boundary, "Site Boundary")
      assertGeometryEquals(exclusion, site.exclusion, "Site Exclusion")
      assertGeometryEquals(stratumBoundary, site.strata[0].boundary, "Stratum Boundary")
      assertGeometryEquals(
          substratumBoundary,
          site.strata[0].substrata[0].boundary,
          "Substratum Boundary",
      )
    }

    @ParameterizedTest
    @CsvSource(
        "Pacific/Honolulu, Asia/Tokyo, 17",
        "null, Pacific/Honolulu, 17",
        "null, null, 18",
        nullValues = ["null"],
    )
    fun `includes latest observation details`(
        timeZone: ZoneId?,
        orgTimeZone: ZoneId?,
        completedDay: Int,
    ) {
      val organizationId = insertOrganization(timeZone = orgTimeZone)
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
              initialPlantingDensity = BigDecimal.TWO,
              targetPlantDensity = BigDecimal.ONE,
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
              initialPlantingDensity = BigDecimal.ONE,
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
      // Claimed but not completed in the latest observation; must not be counted.
      insertObservationPlot(
          monitoringPlotId = monitoringPlotId121,
          monitoringPlotHistoryId = plotHistoryIds[monitoringPlotId121]!!,
          statusId = ObservationPlotStatus.Claimed,
      )

      insertObservation(
          observationType = ObservationType.BiomassMeasurements,
          completedTime = Instant.parse("2023-01-31T00:00:00Z"),
      )
      insertObservationRequestedSubstratum(substratumId = substratumId12)
      insertObservationPlot(
          monitoringPlotId = monitoringPlotId121,
          completedBy = user.userId,
          completedTime = Instant.parse("2023-01-31T00:00:00Z"),
      )

      val completedBaselineId =
          insertObservation(
              observationType = ObservationType.BaselineSurvey,
              completedTime = Instant.parse("2023-01-31T00:00:00Z"),
          )
      insertObservationPlot(
          monitoringPlotId = monitoringPlotId111,
          completedBy = user.userId,
          completedTime = Instant.parse("2023-01-15T05:00:00Z"),
      )
      insertObservationPlot(
          monitoringPlotId = monitoringPlotId112,
          completedBy = user.userId,
          completedTime = Instant.parse("2023-01-16T05:00:00Z"),
      )
      insertObservationPlot(
          monitoringPlotId = monitoringPlotId121,
          statusId = ObservationPlotStatus.Claimed,
      )
      insertObservationPlot(
          monitoringPlotId = monitoringPlotId2,
          completedBy = user.userId,
          completedTime = Instant.parse("2023-01-18T05:00:00Z"),
      )

      val abandonedBaselineId =
          insertObservation(
              observationType = ObservationType.BaselineSurvey,
              state = ObservationState.Abandoned,
              completedTime = Instant.parse("2023-01-31T00:00:00Z"),
          )
      insertObservationRequestedSubstratum(substratumId = substratumId12)
      insertObservationPlot(
          monitoringPlotId = monitoringPlotId111,
          completedBy = user.userId,
          completedTime = Instant.parse("2023-01-16T08:00:00Z"),
      )
      insertObservationPlot(
          monitoringPlotId = monitoringPlotId121,
          statusId = ObservationPlotStatus.Claimed,
      )
      insertObservationPlot(
          monitoringPlotId = monitoringPlotId2,
          completedBy = user.userId,
          completedTime = Instant.parse("2023-01-16T05:00:00Z"),
      )

      val upcomingBaselineId =
          insertObservation(
              observationType = ObservationType.BaselineSurvey,
              state = ObservationState.Upcoming,
              startDate = LocalDate.of(2023, 1, 16),
          )
      insertObservationRequestedSubstratum(substratumId = substratumId12)

      for (state in listOf(ObservationState.Completed, ObservationState.Abandoned)) {
        insertObservation(
            observationType = ObservationType.BaselineSurvey,
            state = state,
            startDate = LocalDate.of(2023, 1, 30),
            completedTime = Instant.parse("2023-01-31T00:00:00Z"),
        )
        for (substratumId in listOf(substratumId11, substratumId12, substratumId2)) {
          insertObservationRequestedSubstratum(substratumId = substratumId)
        }
      }

      /* (x) - for latest (o) for observed but not the latest, (c) claimed but not completed.

        Observations         | 1 | 2 |
        ---------------------|---|---|
        Planting Site        | o | x |
        | - Stratum 1        | o | x |
        | - - Substratum 1-1 | x |   |
        | - - - Plot 1-1-1   | x |   |
        | - - - Plot 1-1-2   |   |   |
        | - - Substratum 1-2 | o | x |
        | - - - Plot 1-2-1   | x | c |
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
              strata =
                  listOf(
                      ExistingStratumModel(
                          areaHa = BigDecimal.TEN,
                          boundary = multiPolygon(2.0),
                          boundaryModifiedTime = boundaryModifiedTime,
                          id = stratumId1,
                          initialPlantingDensity = BigDecimal.TWO,
                          latestObservationCompletedTime = Instant.ofEpochSecond(2000),
                          latestObservationId = observationId2,
                          name = "S1",
                          substrata =
                              listOf(
                                  SubstratumModel(
                                      areaHa = BigDecimal.ONE,
                                      boundary = multiPolygon(1.0),
                                      id = substratumId11,
                                      latestBaselineSurveyDate =
                                          LocalDate.of(2023, 1, completedDay - 2),
                                      latestBaselineSurveyId = abandonedBaselineId,
                                      latestBaselineSurveyStatus = ObservationState.Abandoned,
                                      latestObservationCompletedTime = Instant.ofEpochSecond(1000),
                                      latestObservationId = observationId1,
                                      latestObservationNumPlots = 1,
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
                                      latestBaselineSurveyDate = LocalDate.of(2023, 1, 16),
                                      latestBaselineSurveyId = upcomingBaselineId,
                                      latestBaselineSurveyStatus = ObservationState.Upcoming,
                                      latestObservationCompletedTime = Instant.ofEpochSecond(2000),
                                      latestObservationId = observationId2,
                                      latestObservationNumPlots = 1,
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
                          targetPlantDensity = BigDecimal.ONE,
                      ),
                      ExistingStratumModel(
                          areaHa = BigDecimal.TEN,
                          boundary = multiPolygon(2.0),
                          boundaryModifiedTime = boundaryModifiedTime,
                          id = stratumId2,
                          initialPlantingDensity = BigDecimal.ONE,
                          latestObservationCompletedTime = Instant.ofEpochSecond(2000),
                          latestObservationId = observationId2,
                          name = "S2",
                          substrata =
                              listOf(
                                  SubstratumModel(
                                      areaHa = BigDecimal.ONE,
                                      boundary = multiPolygon(1.0),
                                      id = substratumId2,
                                      latestBaselineSurveyDate =
                                          LocalDate.of(2023, 1, completedDay),
                                      latestBaselineSurveyId = completedBaselineId,
                                      latestBaselineSurveyStatus = ObservationState.Completed,
                                      latestObservationCompletedTime = Instant.ofEpochSecond(2000),
                                      latestObservationId = observationId2,
                                      latestObservationNumPlots = 1,
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
