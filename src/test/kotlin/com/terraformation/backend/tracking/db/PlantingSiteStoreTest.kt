package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.mockUser
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.Month
import java.time.ZoneId
import kotlin.math.roundToInt
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.locationtech.jts.geom.Geometry
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()
  override val tablesToResetSequences = listOf(PLANTING_SITES, PLANTING_ZONES, PLANTING_SUBZONES)

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val store: PlantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        dslContext,
        eventPublisher,
        ParentStore(dslContext),
        plantingSitesDao,
        plantingSubzonesDao,
        plantingZonesDao)
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
    every { user.canReadPlantingSubzone(any()) } returns true
    every { user.canReadPlantingZone(any()) } returns true
    every { user.canReadProject(any()) } returns true
    every { user.canReadOrganization(any()) } returns true
    every { user.canUpdatePlantingSite(any()) } returns true
    every { user.canUpdatePlantingSubzone(any()) } returns true
    every { user.canUpdatePlantingZone(any()) } returns true
  }

  @Test
  fun `fetchSiteById honors depth`() {
    val plantingSiteId = insertPlantingSite(boundary = multiPolygon(3.0), timeZone = timeZone)
    val plantingZoneId = insertPlantingZone(boundary = multiPolygon(2.0))
    val plantingSubzoneId = insertPlantingSubzone(boundary = multiPolygon(1.0))
    val monitoringPlotId = insertMonitoringPlot(boundary = polygon(0.1))

    val expectedWithSite =
        PlantingSiteModel(
            boundary = multiPolygon(3.0),
            description = null,
            id = plantingSiteId,
            name = "Site 1",
            organizationId = organizationId,
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
                        errorMargin = PlantingSiteImporter.DEFAULT_ERROR_MARGIN,
                        id = plantingZoneId,
                        name = "Z1",
                        numPermanentClusters = PlantingSiteImporter.DEFAULT_NUM_PERMANENT_CLUSTERS,
                        numTemporaryPlots = PlantingSiteImporter.DEFAULT_NUM_TEMPORARY_PLOTS,
                        plantingSubzones = emptyList(),
                        studentsT = PlantingSiteImporter.DEFAULT_STUDENTS_T,
                        targetPlantingDensity = BigDecimal.ONE,
                        variance = PlantingSiteImporter.DEFAULT_VARIANCE,
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
                                                    name = "1",
                                                    fullName = "Z1-1-1")),
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
    val plantingZoneId = insertPlantingZone(boundary = zoneBoundary3857)
    val plantingSubzoneId = insertPlantingSubzone(boundary = subzoneBoundary3857)
    val monitoringPlotId = insertMonitoringPlot(boundary = monitoringPlotBoundary3857)

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
                        areaHa = BigDecimal.TEN,
                        boundary = zoneBoundary4326,
                        errorMargin = PlantingSiteImporter.DEFAULT_ERROR_MARGIN,
                        id = plantingZoneId,
                        name = "Z1",
                        numPermanentClusters = PlantingSiteImporter.DEFAULT_NUM_PERMANENT_CLUSTERS,
                        numTemporaryPlots = PlantingSiteImporter.DEFAULT_NUM_TEMPORARY_PLOTS,
                        studentsT = PlantingSiteImporter.DEFAULT_STUDENTS_T,
                        targetPlantingDensity = BigDecimal.ONE,
                        variance = PlantingSiteImporter.DEFAULT_VARIANCE,
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
                                                name = "1",
                                                fullName = "Z1-1-1")),
                                )))))

    val actual = store.fetchSiteById(plantingSiteId, PlantingSiteDepth.Plot)

    if (!expected.equals(actual, 0.000001)) {
      assertEquals(expected, actual)
    }
  }

  @Test
  fun `fetchSiteById throws exception if no permission to read planting site`() {
    val plantingSiteId = insertPlantingSite()

    every { user.canReadPlantingSite(any()) } returns false

    assertThrows<PlantingSiteNotFoundException> {
      store.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
    }
  }

  @Test
  fun `countReportedPlantsInSubzones returns subzones with nursery deliveries`() {
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

  @Nested
  inner class CreatePlantingSite {

    @Test
    fun `inserts new site`() {
      val projectId = insertProject()
      val model =
          store.createPlantingSite(
              description = "description",
              name = "name",
              organizationId = organizationId,
              plantingSeasonEndMonth = Month.JULY,
              plantingSeasonStartMonth = Month.APRIL,
              projectId = projectId,
              timeZone = timeZone,
          )

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
                  plantingSeasonEndMonth = Month.JULY,
                  plantingSeasonStartMonth = Month.APRIL,
                  projectId = projectId,
                  timeZone = timeZone,
              )),
          plantingSitesDao.findAll(),
          "Planting sites")

      assertEquals(emptyList<PlantingZonesRow>(), plantingZonesDao.findAll(), "Planting zones")
    }

    @Test
    fun `throws exception if no permission`() {
      every { user.canCreatePlantingSite(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.createPlantingSite(
            description = null,
            name = "name",
            organizationId = organizationId,
            projectId = null,
            timeZone = null,
        )
      }
    }

    @Test
    fun `throws exception if project is in a different organization`() {
      val otherOrganizationId = OrganizationId(2)
      insertOrganization(otherOrganizationId)
      val projectId = insertProject(organizationId = otherOrganizationId)

      assertThrows<ProjectInDifferentOrganizationException> {
        store.createPlantingSite(
            description = null,
            organizationId = organizationId,
            name = "name",
            projectId = projectId,
            timeZone = null,
        )
      }
    }
  }

  @Nested
  inner class UpdatePlantingSite {
    @Test
    fun `updates values`() {
      val initialModel =
          store.createPlantingSite(
              description = null,
              name = "initial name",
              organizationId = organizationId,
              projectId = null,
              timeZone = timeZone,
          )

      val newTimeZone = insertTimeZone("Europe/Paris")
      val now = Instant.ofEpochSecond(1000)
      clock.instant = now

      store.updatePlantingSite(initialModel.id) { model ->
        model.copy(
            description = "new description",
            name = "new name",
            plantingSeasonEndMonth = Month.MARCH,
            plantingSeasonStartMonth = Month.DECEMBER,
            timeZone = newTimeZone,
        )
      }

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
                  plantingSeasonEndMonth = Month.MARCH,
                  plantingSeasonStartMonth = Month.DECEMBER,
                  timeZone = newTimeZone,
              )),
          plantingSitesDao.findAll(),
          "Planting sites")
    }

    @Test
    fun `publishes event if time zone updated`() {
      val plantingSiteId = insertPlantingSite(timeZone = timeZone)
      val initialModel = store.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
      val newTimeZone = insertTimeZone("Europe/Paris")

      store.updatePlantingSite(plantingSiteId) { it.copy(timeZone = newTimeZone) }

      val expectedEvent =
          PlantingSiteTimeZoneChangedEvent(initialModel.copy(timeZone = newTimeZone))

      eventPublisher.assertEventPublished(expectedEvent)
    }

    @Test
    fun `does not publish event if time zone not updated`() {
      val plantingSiteId = insertPlantingSite(timeZone = timeZone)

      store.updatePlantingSite(plantingSiteId) { it.copy(description = "edited") }

      eventPublisher.assertEventNotPublished(PlantingSiteTimeZoneChangedEvent::class.java)
    }

    @Test
    fun `throws exception if no permission`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canUpdatePlantingSite(any()) } returns false

      assertThrows<AccessDeniedException> { store.updatePlantingSite(plantingSiteId) { it } }
    }

    @Test
    fun `throws exception if project is in a different organization`() {
      val plantingSiteId = insertPlantingSite()
      val otherOrganizationId = OrganizationId(2)
      insertOrganization(otherOrganizationId)
      val otherOrgProjectId = insertProject(organizationId = otherOrganizationId)

      assertThrows<ProjectInDifferentOrganizationException> {
        store.updatePlantingSite(plantingSiteId) { it.copy(projectId = otherOrgProjectId) }
      }
    }
  }

  @Nested
  inner class UpdatePlantingSubzone {
    @Test
    fun `sets completed time to current time if not set previously`() {
      insertPlantingSite()
      insertPlantingZone()
      val plantingSubzoneId = insertPlantingSubzone()

      val initial = plantingSubzonesDao.fetchOneById(plantingSubzoneId)!!

      val now = Instant.ofEpochSecond(5000)
      clock.instant = now

      store.updatePlantingSubzone(plantingSubzoneId) { row ->
        row.copy(plantingCompletedTime = Instant.EPOCH)
      }

      val expected = initial.copy(plantingCompletedTime = now, modifiedTime = now)
      val actual = plantingSubzonesDao.fetchOneById(plantingSubzoneId)!!

      assertEquals(expected, actual)
    }

    @Test
    fun `retains existing non-null completed time`() {
      val initialPlantingCompletedTime = Instant.ofEpochSecond(5)

      insertPlantingSite()
      insertPlantingZone()
      val plantingSubzoneId =
          insertPlantingSubzone(plantingCompletedTime = initialPlantingCompletedTime)

      val initial = plantingSubzonesDao.fetchOneById(plantingSubzoneId)!!

      val now = Instant.ofEpochSecond(5000)
      clock.instant = now

      store.updatePlantingSubzone(plantingSubzoneId) { row ->
        row.copy(plantingCompletedTime = now)
      }

      val expected =
          initial.copy(plantingCompletedTime = initialPlantingCompletedTime, modifiedTime = now)
      val actual = plantingSubzonesDao.fetchOneById(plantingSubzoneId)!!

      assertEquals(expected, actual)
    }

    @Test
    fun `clears completed time`() {
      insertPlantingSite()
      insertPlantingZone()
      val plantingSubzoneId =
          insertPlantingSubzone(plantingCompletedTime = Instant.ofEpochSecond(5))

      val initial = plantingSubzonesDao.fetchOneById(plantingSubzoneId)!!

      val now = Instant.ofEpochSecond(5000)
      clock.instant = now

      store.updatePlantingSubzone(plantingSubzoneId) { row ->
        row.copy(plantingCompletedTime = null)
      }

      val expected = initial.copy(plantingCompletedTime = null, modifiedTime = now)
      val actual = plantingSubzonesDao.fetchOneById(plantingSubzoneId)!!

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission`() {
      insertPlantingSite()
      insertPlantingZone()
      val plantingSubzoneId = insertPlantingSubzone()

      every { user.canUpdatePlantingSubzone(any()) } returns false

      assertThrows<AccessDeniedException> { store.updatePlantingSubzone(plantingSubzoneId) { it } }
    }
  }

  @Test
  fun `updatePlantingZone updates editable values`() {
    val createdTime = Instant.ofEpochSecond(1000)
    val createdBy = UserId(100)
    val plantingSiteId = insertPlantingSite()

    insertUser(createdBy)
    val initialRow =
        PlantingZonesRow(
            areaHa = BigDecimal.ONE,
            boundary = multiPolygon(1.0),
            createdBy = createdBy,
            createdTime = createdTime,
            errorMargin = BigDecimal.TWO,
            plantingSiteId = plantingSiteId,
            modifiedBy = createdBy,
            modifiedTime = createdTime,
            name = "initial",
            numPermanentClusters = 1,
            numTemporaryPlots = 2,
            studentsT = BigDecimal.ONE,
            targetPlantingDensity = BigDecimal.ONE,
            variance = BigDecimal.ZERO,
        )

    plantingZonesDao.insert(initialRow)
    val plantingZoneId = initialRow.id!!

    val newErrorMargin = BigDecimal(10)
    val newStudentsT = BigDecimal(11)
    val newVariance = BigDecimal(12)
    val newPermanent = 13
    val newTemporary = 14
    val newTargetPlantingDensity = BigDecimal(13)

    val expected =
        initialRow.copy(
            errorMargin = newErrorMargin,
            modifiedBy = user.userId,
            modifiedTime = clock.instant(),
            numPermanentClusters = newPermanent,
            numTemporaryPlots = newTemporary,
            studentsT = newStudentsT,
            targetPlantingDensity = newTargetPlantingDensity,
            variance = newVariance,
        )

    store.updatePlantingZone(plantingZoneId) {
      it.copy(
          // Editable
          errorMargin = newErrorMargin,
          numPermanentClusters = newPermanent,
          numTemporaryPlots = newTemporary,
          studentsT = newStudentsT,
          targetPlantingDensity = newTargetPlantingDensity,
          variance = newVariance,
          // Not editable
          createdBy = user.userId,
          createdTime = Instant.ofEpochSecond(5000),
          modifiedBy = createdBy,
          modifiedTime = Instant.ofEpochSecond(5000),
          name = "bogus",
      )
    }

    assertEquals(expected, plantingZonesDao.fetchOneById(plantingZoneId))
  }

  @Test
  fun `updatePlantingZone throws exception if no permission`() {
    insertPlantingSite()
    val plantingZoneId = insertPlantingZone()

    every { user.canUpdatePlantingZone(plantingZoneId) } returns false

    assertThrows<AccessDeniedException> { store.updatePlantingZone(plantingZoneId) { it } }
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

  @Nested
  inner class FetchPermanentPlotIds {
    @Test
    fun `filters out permanent clusters whose subzones are not all planted`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite()
      val plantingZoneId = insertPlantingZone()
      val plantedSubzoneId = insertPlantingSubzone()
      insertWithdrawal()
      insertDelivery()
      insertPlanting(plantingSiteId = inserted.plantingSiteId, plantingSubzoneId = plantedSubzoneId)
      val clusterInPlantedSubzone =
          (1..4).map { insertMonitoringPlot(permanentCluster = 1, permanentClusterSubplot = it) }

      // Cluster that straddles a planted and an unplanted subzone
      (1..2).map { insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = it) }
      insertPlantingSubzone()
      (3..4).map { insertMonitoringPlot(permanentCluster = 2, permanentClusterSubplot = it) }

      // Cluster in unplanted subzone
      (1..4).map { insertMonitoringPlot(permanentCluster = 3, permanentClusterSubplot = it) }

      val expected = clusterInPlantedSubzone.toSet()
      val actual = store.fetchPermanentPlotIds(plantingZoneId, 3)

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission to read planting zone`() {
      insertPlantingSite()
      val plantingZoneId = insertPlantingZone()

      every { user.canReadPlantingZone(plantingZoneId) } returns false

      assertThrows<PlantingZoneNotFoundException> { store.fetchPermanentPlotIds(plantingZoneId, 1) }
    }
  }

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
          )

      val actual = store.countReportedPlants(plantingSiteId)

      assertEquals(expected, actual)
      assertNull(actual.progressPercent, "Progress%")
    }

    @Test
    fun `returns correct zone-level totals`() {
      val plantingSiteId = insertPlantingSite()
      val plantingZoneId1 =
          insertPlantingZone(areaHa = BigDecimal(10), targetPlantingDensity = BigDecimal(2))
      insertSpecies()
      insertPlantingZonePopulation(plantsSinceLastObservation = 1, totalPlants = 10)
      insertPlantingSitePopulation(plantsSinceLastObservation = 1, totalPlants = 10)
      insertSpecies()
      insertPlantingZonePopulation(plantsSinceLastObservation = 2, totalPlants = 20)
      val plantingZoneId2 =
          insertPlantingZone(areaHa = BigDecimal(101), targetPlantingDensity = BigDecimal(4))
      insertPlantingZonePopulation(plantsSinceLastObservation = 4, totalPlants = 40)
      insertPlantingSitePopulation(plantsSinceLastObservation = 6, totalPlants = 60)
      insertSpecies()
      insertPlantingZonePopulation(plantsSinceLastObservation = 8, totalPlants = 80)
      insertPlantingSitePopulation(plantsSinceLastObservation = 8, totalPlants = 80)
      val emptyPlantingZoneId =
          insertPlantingZone(areaHa = BigDecimal(50), targetPlantingDensity = BigDecimal(5))

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
                      ),
                      PlantingSiteReportedPlantTotals.PlantingZone(
                          id = plantingZoneId2,
                          plantsSinceLastObservation = 12,
                          targetPlants = 404,
                          totalPlants = 120,
                      ),
                      PlantingSiteReportedPlantTotals.PlantingZone(
                          id = emptyPlantingZoneId,
                          plantsSinceLastObservation = 0,
                          targetPlants = 250,
                          totalPlants = 0,
                      ),
                  ),
              plantsSinceLastObservation = 15,
              totalPlants = 150,
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
}
