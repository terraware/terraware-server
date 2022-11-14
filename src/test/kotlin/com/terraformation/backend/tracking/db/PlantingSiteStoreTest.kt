package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.db.tracking.tables.references.PLOTS
import com.terraformation.backend.mockUser
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.tracking.model.PlotModel
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.InstantSource
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
  override val tablesToResetSequences = listOf(PLANTING_SITES, PLANTING_ZONES, PLOTS)

  private val clock: InstantSource = mockk()
  private val store: PlantingSiteStore by lazy {
    PlantingSiteStore(clock, dslContext, plantingSitesDao)
  }

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()

    every { clock.instant() } returns Instant.EPOCH
    every { user.canCreatePlantingSite(any()) } returns true
    every { user.canReadPlantingSite(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canUpdatePlantingSite(any()) } returns true
  }

  @Test
  fun `fetchSiteById returns planting zone and plot models`() {
    val plantingSiteId = insertPlantingSite(boundary = multiPolygon(3.0))
    val plantingZoneId =
        insertPlantingZone(boundary = multiPolygon(2.0), plantingSiteId = plantingSiteId)
    val plotId = insertPlot(boundary = multiPolygon(1.0), plantingZoneId = plantingZoneId)

    val expected =
        PlantingSiteModel(
            boundary = multiPolygon(3.0),
            description = null,
            id = plantingSiteId,
            name = "Site 1",
            plantingZones =
                listOf(
                    PlantingZoneModel(
                        boundary = multiPolygon(2.0),
                        id = plantingZoneId,
                        name = "Z1",
                        plots =
                            listOf(
                                PlotModel(
                                    boundary = multiPolygon(1.0),
                                    id = plotId,
                                    fullName = "Z1-1",
                                    name = "1")))))

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
    fun Geometry.to3857() = JTS.transform(this, transform4326To3857).also { it.srid = 3857 }

    val siteBoundary4326 = multiPolygon(30.0)
    val zoneBoundary4326 = multiPolygon(20.0)
    val plotBoundary4326 = multiPolygon(10.0)

    val siteBoundary3857 = siteBoundary4326.to3857()
    val zoneBoundary3857 = zoneBoundary4326.to3857()
    val plotBoundary3857 = plotBoundary4326.to3857()

    val plantingSiteId = insertPlantingSite(boundary = siteBoundary3857)
    val plantingZoneId =
        insertPlantingZone(boundary = zoneBoundary3857, plantingSiteId = plantingSiteId)
    val plotId = insertPlot(boundary = plotBoundary3857, plantingZoneId = plantingZoneId)

    val expected =
        PlantingSiteModel(
            boundary = siteBoundary4326,
            description = null,
            id = plantingSiteId,
            name = "Site 1",
            plantingZones =
                listOf(
                    PlantingZoneModel(
                        boundary = zoneBoundary4326,
                        id = plantingZoneId,
                        name = "Z1",
                        plots =
                            listOf(
                                PlotModel(
                                    boundary = plotBoundary4326,
                                    id = plotId,
                                    fullName = "Z1-1",
                                    name = "1")))))

    val actual = store.fetchSiteById(plantingSiteId)

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
    val model = store.createPlantingSite(organizationId, "name", "description")

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
            )),
        plantingSitesDao.findAll(),
        "Planting sites")

    assertEquals(emptyList<PlantingZonesRow>(), plantingZonesDao.findAll(), "Planting zones")
  }

  @Test
  fun `createPlantingSite throws exception if no permission`() {
    every { user.canCreatePlantingSite(any()) } returns false

    assertThrows<AccessDeniedException> { store.createPlantingSite(organizationId, "name", null) }
  }

  @Test
  fun `updatePlantingSite updates values`() {
    val initialModel = store.createPlantingSite(organizationId, "initial name", null)

    val now = Instant.ofEpochSecond(1000)
    every { clock.instant() } returns now

    store.updatePlantingSite(initialModel.id, "new name", "new description")

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
            )),
        plantingSitesDao.findAll(),
        "Planting sites")
  }

  @Test
  fun `updatePlantingSite throws exception if no permission`() {
    val plantingSiteId = insertPlantingSite()

    every { user.canUpdatePlantingSite(any()) } returns false

    assertThrows<AccessDeniedException> {
      store.updatePlantingSite(plantingSiteId, "new name", "new description")
    }
  }
}
