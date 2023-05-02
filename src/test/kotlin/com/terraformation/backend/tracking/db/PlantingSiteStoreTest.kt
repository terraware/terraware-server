package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.mockUser
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import io.mockk.every
import java.time.Instant
import java.time.ZoneId
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Geometry
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  override val tablesToResetSequences = listOf(PLANTING_SITES, PLANTING_ZONES, PLANTING_SUBZONES)

  private val clock = TestClock()
  private val store: PlantingSiteStore by lazy {
    PlantingSiteStore(clock, dslContext, plantingSitesDao)
  }

  private lateinit var timeZone: ZoneId

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()
    timeZone = insertTimeZone()

    every { user.canCreatePlantingSite(any()) } returns true
    every { user.canMovePlantingSiteToAnyOrg(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canUpdatePlantingSite(any()) } returns true
  }

  @Test
  fun `fetchSiteById returns planting zone and plot models`() {
    val plantingSiteId = insertPlantingSite(boundary = multiPolygon(3.0), timeZone = timeZone)
    val plantingZoneId =
        insertPlantingZone(boundary = multiPolygon(2.0), plantingSiteId = plantingSiteId)
    val plantingSubzoneId =
        insertPlantingSubzone(
            boundary = multiPolygon(1.0),
            plantingSiteId = plantingSiteId,
            plantingZoneId = plantingZoneId)
    val monitoringPlotId =
        insertMonitoringPlot(boundary = polygon(0.1), plantingSubzoneId = plantingSubzoneId)

    val expected =
        PlantingSiteModel(
            boundary = multiPolygon(3.0),
            description = null,
            id = plantingSiteId,
            name = "Site 1",
            organizationId = organizationId,
            plantingZones =
                listOf(
                    PlantingZoneModel(
                        boundary = multiPolygon(2.0),
                        id = plantingZoneId,
                        name = "Z1",
                        plantingSubzones =
                            listOf(
                                PlantingSubzoneModel(
                                    boundary = multiPolygon(1.0),
                                    id = plantingSubzoneId,
                                    fullName = "Z1-1",
                                    name = "1",
                                    listOf(
                                        MonitoringPlotModel(
                                            boundary = polygon(0.1),
                                            id = monitoringPlotId,
                                            name = "1",
                                            fullName = "Z1-1-1")),
                                )))),
            timeZone = timeZone,
        )

    val actual = store.fetchSiteById(plantingSiteId, includeSubzones = true, includePlots = true)

    if (!expected.equals(actual, 0.00001)) {
      assertEquals(expected, actual)
    }
  }

  @Test
  fun `fetchSiteById can omit monitoring plots`() {
    val plantingSiteId = insertPlantingSite(boundary = multiPolygon(3.0), timeZone = timeZone)
    val plantingZoneId =
        insertPlantingZone(boundary = multiPolygon(2.0), plantingSiteId = plantingSiteId)
    val plantingSubzoneId =
        insertPlantingSubzone(
            boundary = multiPolygon(1.0),
            plantingSiteId = plantingSiteId,
            plantingZoneId = plantingZoneId)
    insertMonitoringPlot(boundary = polygon(0.1), plantingSubzoneId = plantingSubzoneId)

    val expected =
        PlantingSiteModel(
            boundary = multiPolygon(3.0),
            description = null,
            id = plantingSiteId,
            name = "Site 1",
            organizationId = organizationId,
            plantingZones =
                listOf(
                    PlantingZoneModel(
                        boundary = multiPolygon(2.0),
                        id = plantingZoneId,
                        name = "Z1",
                        plantingSubzones =
                            listOf(
                                PlantingSubzoneModel(
                                    boundary = multiPolygon(1.0),
                                    id = plantingSubzoneId,
                                    fullName = "Z1-1",
                                    name = "1",
                                    emptyList())))),
            timeZone = timeZone,
        )

    val actual = store.fetchSiteById(plantingSiteId, includeSubzones = true)

    if (!expected.equals(actual, 0.00001)) {
      assertEquals(expected, actual)
    }
  }

  @Test
  fun `fetchSiteById omits subzones and monitoring plots by default`() {
    val plantingSiteId = insertPlantingSite(boundary = multiPolygon(3.0), timeZone = timeZone)
    val plantingZoneId =
        insertPlantingZone(boundary = multiPolygon(2.0), plantingSiteId = plantingSiteId)
    val plantingSubzoneId =
        insertPlantingSubzone(
            boundary = multiPolygon(1.0),
            plantingSiteId = plantingSiteId,
            plantingZoneId = plantingZoneId)
    insertMonitoringPlot(boundary = polygon(0.1), plantingSubzoneId = plantingSubzoneId)

    val expected =
        PlantingSiteModel(
            boundary = multiPolygon(3.0),
            description = null,
            id = plantingSiteId,
            name = "Site 1",
            organizationId = organizationId,
            plantingZones =
                listOf(
                    PlantingZoneModel(
                        boundary = multiPolygon(2.0),
                        id = plantingZoneId,
                        name = "Z1",
                        plantingSubzones = emptyList())),
            timeZone = timeZone,
        )

    val actual = store.fetchSiteById(plantingSiteId)

    if (!expected.equals(actual, 0.00001)) {
      assertEquals(expected, actual)
    }
  }

  @Test
  fun `fetchSiteById transforms coordinates to SRID 4326`() {
    val crsFactory = CRS.getAuthorityFactory(true)
    val crs3857 = crsFactory.createCoordinateReferenceSystem("EPSG:3857")
    val crs4326 = crsFactory.createCoordinateReferenceSystem("EPSG:4326")
    val transform4326To3857 = CRS.findMathTransform(crs4326, crs3857)

    @Suppress("UNCHECKED_CAST")
    fun <T : Geometry> T.to3857(): T {
      return JTS.transform(this, transform4326To3857).also { it.srid = 3857 } as T
    }

    val siteBoundary4326 = multiPolygon(30.0)
    val zoneBoundary4326 = multiPolygon(20.0)
    val subzoneBoundary4326 = multiPolygon(10.0)
    val monitoringPlotBoundary4326 = polygon(1.0)

    val siteBoundary3857 = siteBoundary4326.to3857()
    val zoneBoundary3857 = zoneBoundary4326.to3857()
    val subzoneBoundary3857 = subzoneBoundary4326.to3857()
    val monitoringPlotBoundary3857 = monitoringPlotBoundary4326.to3857()

    val plantingSiteId = insertPlantingSite(boundary = siteBoundary3857)
    val plantingZoneId =
        insertPlantingZone(boundary = zoneBoundary3857, plantingSiteId = plantingSiteId)
    val plantingSubzoneId =
        insertPlantingSubzone(
            boundary = subzoneBoundary3857,
            plantingSiteId = plantingSiteId,
            plantingZoneId = plantingZoneId)
    val monitoringPlotId =
        insertMonitoringPlot(
            boundary = monitoringPlotBoundary3857, plantingSubzoneId = plantingSubzoneId)

    val expected =
        PlantingSiteModel(
            boundary = siteBoundary4326,
            description = null,
            id = plantingSiteId,
            name = "Site 1",
            organizationId = organizationId,
            plantingZones =
                listOf(
                    PlantingZoneModel(
                        boundary = zoneBoundary4326,
                        id = plantingZoneId,
                        name = "Z1",
                        plantingSubzones =
                            listOf(
                                PlantingSubzoneModel(
                                    boundary = subzoneBoundary4326,
                                    id = plantingSubzoneId,
                                    fullName = "Z1-1",
                                    name = "1",
                                    listOf(
                                        MonitoringPlotModel(
                                            boundary = monitoringPlotBoundary4326,
                                            id = monitoringPlotId,
                                            name = "1",
                                            fullName = "Z1-1-1")),
                                )))))

    val actual = store.fetchSiteById(plantingSiteId, includeSubzones = true, includePlots = true)

    if (!expected.equals(actual, 0.000001)) {
      assertEquals(expected, actual)
    }
  }

  @Test
  fun `fetchSiteById throws exception if no permission to read planting site`() {
    val plantingSiteId = insertPlantingSite()

    every { user.canReadPlantingSite(any()) } returns false

    assertThrows<PlantingSiteNotFoundException> { store.fetchSiteById(plantingSiteId) }
  }

  @Test
  fun `createPlantingSite inserts new site`() {
    val model = store.createPlantingSite(organizationId, "name", "description", timeZone)

    assertEquals(
        listOf(
            PlantingSitesRow(
                id = model.id,
                organizationId = organizationId,
                name = "name",
                description = "description",
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                modifiedBy = user.userId,
                modifiedTime = Instant.EPOCH,
                timeZone = timeZone,
            )),
        plantingSitesDao.findAll(),
        "Planting sites")

    assertEquals(emptyList<PlantingZonesRow>(), plantingZonesDao.findAll(), "Planting zones")
  }

  @Test
  fun `createPlantingSite throws exception if no permission`() {
    every { user.canCreatePlantingSite(any()) } returns false

    assertThrows<AccessDeniedException> {
      store.createPlantingSite(organizationId, "name", null, null)
    }
  }

  @Test
  fun `updatePlantingSite updates values`() {
    val initialModel = store.createPlantingSite(organizationId, "initial name", null, timeZone)

    val newTimeZone = insertTimeZone("Europe/Paris")
    val now = Instant.ofEpochSecond(1000)
    clock.instant = now

    store.updatePlantingSite(initialModel.id, "new name", "new description", newTimeZone)

    assertEquals(
        listOf(
            PlantingSitesRow(
                id = initialModel.id,
                organizationId = organizationId,
                name = "new name",
                description = "new description",
                createdBy = user.userId,
                createdTime = Instant.EPOCH,
                modifiedBy = user.userId,
                modifiedTime = now,
                timeZone = newTimeZone,
            )),
        plantingSitesDao.findAll(),
        "Planting sites")
  }

  @Test
  fun `updatePlantingSite throws exception if no permission`() {
    val plantingSiteId = insertPlantingSite()

    every { user.canUpdatePlantingSite(any()) } returns false

    assertThrows<AccessDeniedException> {
      store.updatePlantingSite(plantingSiteId, "new name", "new description", null)
    }
  }

  @Test
  fun `movePlantingSite updates organization ID`() {
    val otherOrganizationId = insertOrganization(2)
    val plantingSiteId = insertPlantingSite()
    val before = plantingSitesDao.fetchOneById(plantingSiteId)!!
    val newTime = Instant.ofEpochSecond(1000)

    clock.instant = newTime

    store.movePlantingSite(plantingSiteId, otherOrganizationId)

    assertEquals(
        before.copy(
            modifiedTime = newTime,
            organizationId = otherOrganizationId,
        ),
        plantingSitesDao.fetchOneById(plantingSiteId))
  }

  @Test
  fun `movePlantingSite throws exception if no permission`() {
    val plantingSiteId = insertPlantingSite()

    every { user.canMovePlantingSiteToAnyOrg(any()) } returns false

    assertThrows<AccessDeniedException> { store.movePlantingSite(plantingSiteId, organizationId) }
  }
}
