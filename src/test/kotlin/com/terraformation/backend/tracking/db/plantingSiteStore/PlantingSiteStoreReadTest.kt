package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.model.ExistingPlantingSeasonModel
import com.terraformation.backend.tracking.model.ExistingPlantingSiteModel
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.util.toInstant
import io.mockk.every
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.roundToInt
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Geometry

internal class PlantingSiteStoreReadTest : PlantingSiteStoreTest() {
  @Nested
  inner class CountReportedPlants {
    @Test
    fun `returns zero total for sites without plants`() {
      val plantingSiteId = insertPlantingSite()

      val expected =
          PlantingSiteReportedPlantTotals(
              id = plantingSiteId,
              plantingZones = emptyList(),
              plantsSinceLastObservation = 0,
              totalPlants = 0,
              totalSpecies = 0,
          )

      val actual = store.countReportedPlants(plantingSiteId)

      assertEquals(expected, actual)
    }

    @Test
    fun `returns site-level totals for sites without zones`() {
      val plantingSiteId = insertPlantingSite()
      insertSpecies()
      insertPlantingSitePopulation(plantsSinceLastObservation = 1, totalPlants = 10)
      insertSpecies()
      insertPlantingSitePopulation(plantsSinceLastObservation = 2, totalPlants = 20)

      val expected =
          PlantingSiteReportedPlantTotals(
              id = plantingSiteId,
              plantingZones = emptyList(),
              plantsSinceLastObservation = 3,
              totalPlants = 30,
              totalSpecies = 2,
          )

      val actual = store.countReportedPlants(plantingSiteId)

      assertEquals(expected, actual)
      assertNull(actual.progressPercent, "Progress%")
    }

    @Test
    fun `returns correct zone-level and subzone-level totals`() {
      val plantingSiteId = insertPlantingSite()

      val plantingZoneId1 =
          insertPlantingZone(areaHa = BigDecimal(10), targetPlantingDensity = BigDecimal(2))
      val plantingSubzoneId1a = insertPlantingSubzone()
      insertSpecies()
      insertPlantingSubzonePopulation(totalPlants = 5)
      insertPlantingZonePopulation(plantsSinceLastObservation = 1, totalPlants = 10)
      insertPlantingSitePopulation(plantsSinceLastObservation = 1, totalPlants = 10)
      val plantingSubzoneId1b = insertPlantingSubzone()
      insertPlantingSubzonePopulation(totalPlants = 5)
      insertSpecies()
      insertPlantingSubzonePopulation(totalPlants = 20)
      insertPlantingZonePopulation(plantsSinceLastObservation = 2, totalPlants = 20)

      val plantingZoneId2 =
          insertPlantingZone(areaHa = BigDecimal(101), targetPlantingDensity = BigDecimal(4))
      val plantingSubzoneId2 = insertPlantingSubzone()
      insertPlantingSubzonePopulation(totalPlants = 50)
      insertPlantingZonePopulation(plantsSinceLastObservation = 4, totalPlants = 40)
      insertPlantingSitePopulation(plantsSinceLastObservation = 6, totalPlants = 60)
      insertSpecies()
      insertPlantingSubzonePopulation(totalPlants = 55)
      insertPlantingZonePopulation(plantsSinceLastObservation = 8, totalPlants = 80)
      insertPlantingSitePopulation(plantsSinceLastObservation = 8, totalPlants = 80)

      val emptyPlantingZoneId =
          insertPlantingZone(areaHa = BigDecimal(50), targetPlantingDensity = BigDecimal(5))
      insertPlantingSubzone()

      // Note that the zone/subzone/site totals don't all add up correctly here; that's intentional
      // to make sure the values are coming from the right places.

      val expected =
          PlantingSiteReportedPlantTotals(
              id = plantingSiteId,
              plantingZones =
                  listOf(
                      PlantingSiteReportedPlantTotals.PlantingZone(
                          id = plantingZoneId1,
                          plantsSinceLastObservation = 3,
                          targetPlants = 20,
                          totalPlants = 30,
                          plantingSubzones =
                              listOf(
                                  PlantingSiteReportedPlantTotals.PlantingSubzone(
                                      id = plantingSubzoneId1a,
                                      totalPlants = 5,
                                  ),
                                  PlantingSiteReportedPlantTotals.PlantingSubzone(
                                      id = plantingSubzoneId1b,
                                      totalPlants = 25,
                                  )),
                      ),
                      PlantingSiteReportedPlantTotals.PlantingZone(
                          id = plantingZoneId2,
                          plantsSinceLastObservation = 12,
                          targetPlants = 404,
                          totalPlants = 120,
                          plantingSubzones =
                              listOf(
                                  PlantingSiteReportedPlantTotals.PlantingSubzone(
                                      id = plantingSubzoneId2,
                                      totalPlants = 105,
                                  ))),
                      PlantingSiteReportedPlantTotals.PlantingZone(
                          id = emptyPlantingZoneId,
                          plantsSinceLastObservation = 0,
                          plantingSubzones = emptyList(),
                          targetPlants = 250,
                          totalPlants = 0,
                      ),
                  ),
              plantsSinceLastObservation = 15,
              totalPlants = 150,
              totalSpecies = 3,
          )

      val actual = store.countReportedPlants(plantingSiteId)

      assertEquals(expected, actual)
      assertEquals(150, actual.plantingZones[0].progressPercent, "Progress% for zone 1")
      assertEquals(
          (29.7).roundToInt(),
          actual.plantingZones[1].progressPercent,
          "Progress% for zone 2 should be rounded up")
      assertEquals(
          (150.0 / (20.0 + 404.0 + 250.0) * 100.0).roundToInt(),
          actual.progressPercent,
          "Progress% for site")
    }

    @Test
    fun `throws exception if no permission to read planting site`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> { store.countReportedPlants(plantingSiteId) }
    }
  }

  @Nested
  inner class CountReportedPlantsInSubzones {
    @Test
    fun `returns subzones with nursery deliveries`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      val plantingSiteId = insertPlantingSite()

      insertPlantingZone()
      val plantingSubzoneId11 = insertPlantingSubzone()
      val plantingSubzoneId12 = insertPlantingSubzone()

      insertPlantingZone()
      val plantingSubzoneId21 = insertPlantingSubzone()

      // Original delivery to subzone 12, then reassignment to 11, so 12 shouldn't be counted as
      // planted any more.
      insertWithdrawal(purpose = WithdrawalPurpose.OutPlant)
      insertDelivery()
      insertPlanting(numPlants = 1, plantingSubzoneId = plantingSubzoneId12)
      insertPlanting(
          numPlants = -1,
          plantingTypeId = PlantingType.ReassignmentFrom,
          plantingSubzoneId = plantingSubzoneId12)
      insertPlanting(
          numPlants = 1,
          plantingTypeId = PlantingType.ReassignmentTo,
          plantingSubzoneId = plantingSubzoneId11)
      insertSpecies()
      insertPlanting(numPlants = 2, plantingSubzoneId = plantingSubzoneId21)

      insertWithdrawal(purpose = WithdrawalPurpose.OutPlant)
      insertDelivery()
      insertPlanting(numPlants = 4, plantingSubzoneId = plantingSubzoneId21)

      // Additional planting subzone with no plantings.
      insertPlantingSubzone()

      assertEquals(
          mapOf(plantingSubzoneId11 to 1L, plantingSubzoneId21 to 6L),
          store.countReportedPlantsInSubzones(plantingSiteId))
    }
  }

  @Nested
  inner class FetchSubzoneIdsWithPastPlantings {
    @Test
    fun `returns subzones with nursery deliveries`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()

      val plantingSiteId = insertPlantingSite()

      insertPlantingZone()
      val plantingSubzoneId11 = insertPlantingSubzone()
      val plantingSubzoneId12 = insertPlantingSubzone()

      insertPlantingZone()
      val plantingSubzoneId21 = insertPlantingSubzone()

      // Original delivery to subzone 12, then reassignment to 11. Both 11 and 12 should be counted
      // as having had past plantings.
      insertWithdrawal(purpose = WithdrawalPurpose.OutPlant)
      insertDelivery()
      insertPlanting(numPlants = 1, plantingSubzoneId = plantingSubzoneId12)
      insertPlanting(
          numPlants = -1,
          plantingTypeId = PlantingType.ReassignmentFrom,
          plantingSubzoneId = plantingSubzoneId12)
      insertPlanting(
          numPlants = 1,
          plantingTypeId = PlantingType.ReassignmentTo,
          plantingSubzoneId = plantingSubzoneId11)
      insertSpecies()
      insertPlanting(numPlants = 2, plantingSubzoneId = plantingSubzoneId21)

      insertWithdrawal(purpose = WithdrawalPurpose.OutPlant)
      insertDelivery()
      insertPlanting(numPlants = 4, plantingSubzoneId = plantingSubzoneId21)

      // Additional planting subzone with no plantings.
      insertPlantingSubzone()

      assertEquals(
          setOf(plantingSubzoneId11, plantingSubzoneId12, plantingSubzoneId21),
          store.fetchSubzoneIdsWithPastPlantings(plantingSiteId))
    }
  }

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
      val season1StartDate = LocalDate.of(2023, 6, 1)
      val season1EndDate = LocalDate.of(2023, 7, 31)
      val plantingSeasonId1 =
          insertPlantingSeason(startDate = season1StartDate, endDate = season1EndDate)
      val season2StartDate = LocalDate.of(2023, 1, 1)
      val season2EndDate = LocalDate.of(2023, 1, 31)
      val plantingSeasonId2 =
          insertPlantingSeason(startDate = season2StartDate, endDate = season2EndDate)

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

  @Nested
  inner class HasPlantings {
    @BeforeEach
    fun setUp() {
      every { user.canReadPlantingSite(any()) } returns true
    }

    @Test
    fun `throws exception when no permission to read the planting site`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> { store.hasPlantings(plantingSiteId) }
    }

    @Test
    fun `returns false when there are no plantings in the site`() {
      val plantingSiteId = insertPlantingSite()

      assertFalse(store.hasPlantings(plantingSiteId))
    }

    @Test
    fun `returns true when there are plantings in the site`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      val plantingSiteId = insertPlantingSite()
      insertWithdrawal()
      insertDelivery()
      insertPlanting()

      assertTrue(store.hasPlantings(plantingSiteId))
    }
  }

  @Nested
  inner class HasSubzonePlantings {
    @BeforeEach
    fun setUp() {
      every { user.canReadPlantingSite(any()) } returns true
    }

    @Test
    fun `throws exception when no permission to read the planting site`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> { store.hasSubzonePlantings(plantingSiteId) }
    }

    @Test
    fun `returns false when there are no plantings in subzones for a site without subzones`() {
      val plantingSiteId = insertPlantingSite()

      assertFalse(store.hasSubzonePlantings(plantingSiteId))
    }

    @Test
    fun `returns false when there are no plantings in subzones for a site with subzones`() {
      val plantingSiteId = insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()

      assertFalse(store.hasSubzonePlantings(plantingSiteId))
    }

    @Test
    fun `returns true when there are plantings in subzones`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      val plantingSiteId = insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()
      insertWithdrawal()
      insertDelivery()
      insertPlanting()

      assertTrue(store.hasSubzonePlantings(plantingSiteId))
    }
  }
}
