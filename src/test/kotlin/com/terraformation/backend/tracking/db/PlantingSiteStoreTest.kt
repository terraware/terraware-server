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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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

    assertEquals(expected, actual)
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
