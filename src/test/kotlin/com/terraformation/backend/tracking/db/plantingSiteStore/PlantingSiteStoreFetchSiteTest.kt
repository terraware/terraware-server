package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.model.ExistingPlantingSeasonModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.util.toInstant
import io.mockk.every
import java.math.BigDecimal
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
      val plantingZoneId =
          insertPlantingZone(boundary = multiPolygon(2.0), extraPermanentClusters = 1)
      val plantingSubzoneId = insertPlantingSubzone(boundary = multiPolygon(1.0))
      val monitoringPlotId = insertMonitoringPlot(boundary = polygon(0.1))
      insertMonitoringPlot(
          boundary = polygon(0.1), isAdHoc = true, name = "Ad hoc plot is not returned")

      val season1StartDate = LocalDate.of(2023, 6, 1)
      val season1EndDate = LocalDate.of(2023, 7, 31)
      val plantingSeasonId1 =
          insertPlantingSeason(startDate = season1StartDate, endDate = season1EndDate)
      val season2StartDate = LocalDate.of(2023, 1, 1)
      val season2EndDate = LocalDate.of(2023, 1, 31)
      val plantingSeasonId2 =
          insertPlantingSeason(startDate = season2StartDate, endDate = season2EndDate)

      val exteriorPlotId = insertMonitoringPlot(boundary = polygon(0.2), plantingSubzoneId = null)

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
              plantingZones = emptyList(),
              timeZone = timeZone,
          )

      val expectedWithZone =
          expectedWithSite.copy(
              plantingZones =
                  listOf(
                      PlantingZoneModel(
                          areaHa = BigDecimal.TEN,
                          boundary = multiPolygon(2.0),
                          extraPermanentClusters = 1,
                          id = plantingZoneId,
                          name = "Z1",
                          plantingSubzones = emptyList(),
                          targetPlantingDensity = BigDecimal.ONE,
                      ),
                  ))

      val expectedWithSubzone =
          expectedWithZone.copy(
              plantingZones =
                  listOf(
                      expectedWithZone.plantingZones[0].copy(
                          plantingSubzones =
                              listOf(
                                  PlantingSubzoneModel(
                                      areaHa = BigDecimal.ONE,
                                      boundary = multiPolygon(1.0),
                                      id = plantingSubzoneId,
                                      fullName = "Z1-1",
                                      name = "1",
                                      plantingCompletedTime = null,
                                      monitoringPlots = emptyList(),
                                  )))),
          )

      val expectedWithPlot =
          expectedWithSubzone.copy(
              exteriorPlots =
                  listOf(
                      MonitoringPlotModel(
                          boundary = polygon(0.2),
                          id = exteriorPlotId,
                          isAdHoc = false,
                          isAvailable = true,
                          name = "2",
                          fullName = "2",
                          sizeMeters = 30)),
              plantingZones =
                  listOf(
                      expectedWithSubzone.plantingZones[0].copy(
                          plantingSubzones =
                              listOf(
                                  expectedWithSubzone.plantingZones[0]
                                      .plantingSubzones[0]
                                      .copy(
                                          monitoringPlots =
                                              listOf(
                                                  MonitoringPlotModel(
                                                      boundary = polygon(0.1),
                                                      id = monitoringPlotId,
                                                      isAdHoc = false,
                                                      isAvailable = true,
                                                      name = "1",
                                                      fullName = "Z1-1-1",
                                                      sizeMeters = 30)),
                                      )))))

      val allExpected =
          mapOf(
              PlantingSiteDepth.Site to expectedWithSite,
              PlantingSiteDepth.Zone to expectedWithZone,
              PlantingSiteDepth.Subzone to expectedWithSubzone,
              PlantingSiteDepth.Plot to expectedWithPlot,
          )

      val allActual =
          PlantingSiteDepth.entries.associateWith { store.fetchSiteById(plantingSiteId, it) }

      if (!allExpected.all { (depth, expected) -> allActual[depth]!!.equals(expected, 0.00001) }) {
        assertEquals(allExpected, allActual)
      }
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
      val zoneBoundary4326 = multiPolygon(20)
      val subzoneBoundary4326 = multiPolygon(10)
      val monitoringPlotBoundary4326 = polygon(1)
      val exclusion4326 = multiPolygon(5)

      val siteBoundary3857 = siteBoundary4326.to3857()
      val zoneBoundary3857 = zoneBoundary4326.to3857()
      val subzoneBoundary3857 = subzoneBoundary4326.to3857()
      val monitoringPlotBoundary3857 = monitoringPlotBoundary4326.to3857()
      val exclusion3857 = exclusion4326.to3857()

      val plantingSiteId =
          insertPlantingSite(boundary = siteBoundary3857, exclusion = exclusion3857)
      val plantingZoneId = insertPlantingZone(boundary = zoneBoundary3857)
      val plantingSubzoneId = insertPlantingSubzone(boundary = subzoneBoundary3857)
      val monitoringPlotId = insertMonitoringPlot(boundary = monitoringPlotBoundary3857)

      val expected =
          PlantingSiteModel(
              boundary = siteBoundary4326,
              description = null,
              exclusion = exclusion4326,
              id = plantingSiteId,
              name = "Site 1",
              organizationId = organizationId,
              plantingZones =
                  listOf(
                      PlantingZoneModel(
                          areaHa = BigDecimal.TEN,
                          boundary = zoneBoundary4326,
                          extraPermanentClusters = 0,
                          id = plantingZoneId,
                          name = "Z1",
                          targetPlantingDensity = BigDecimal.ONE,
                          plantingSubzones =
                              listOf(
                                  PlantingSubzoneModel(
                                      areaHa = BigDecimal.ONE,
                                      boundary = subzoneBoundary4326,
                                      id = plantingSubzoneId,
                                      fullName = "Z1-1",
                                      name = "1",
                                      plantingCompletedTime = null,
                                      monitoringPlots =
                                          listOf(
                                              MonitoringPlotModel(
                                                  boundary = monitoringPlotBoundary4326,
                                                  id = monitoringPlotId,
                                                  isAdHoc = false,
                                                  isAvailable = true,
                                                  name = "1",
                                                  fullName = "Z1-1-1",
                                                  sizeMeters = 30)),
                                  )))))

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
