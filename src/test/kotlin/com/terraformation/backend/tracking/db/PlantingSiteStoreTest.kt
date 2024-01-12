package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.event.PlantingSiteTimeZoneChangedEvent
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.default_schema.FacilityType
import com.terraformation.backend.db.default_schema.NotificationType
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSeasonsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingZonesRow
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SUBZONES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_ZONES
import com.terraformation.backend.mockUser
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.event.PlantingSeasonEndedEvent
import com.terraformation.backend.tracking.event.PlantingSeasonRescheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonScheduledEvent
import com.terraformation.backend.tracking.event.PlantingSeasonStartedEvent
import com.terraformation.backend.tracking.event.PlantingSiteDeletionStartedEvent
import com.terraformation.backend.tracking.model.CannotCreatePastPlantingSeasonException
import com.terraformation.backend.tracking.model.CannotUpdatePastPlantingSeasonException
import com.terraformation.backend.tracking.model.ExistingPlantingSeasonModel
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import com.terraformation.backend.tracking.model.PlantingSeasonTooFarInFutureException
import com.terraformation.backend.tracking.model.PlantingSeasonTooLongException
import com.terraformation.backend.tracking.model.PlantingSeasonTooShortException
import com.terraformation.backend.tracking.model.PlantingSeasonsOverlapException
import com.terraformation.backend.tracking.model.PlantingSiteDepth
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.PlantingSiteReportedPlantTotals
import com.terraformation.backend.tracking.model.PlantingSubzoneModel
import com.terraformation.backend.tracking.model.PlantingZoneModel
import com.terraformation.backend.tracking.model.UpdatedPlantingSeasonModel
import com.terraformation.backend.util.toInstant
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.roundToInt
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.jooq.DAO
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
        monitoringPlotsDao,
        ParentStore(dslContext),
        plantingSeasonsDao,
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
    val plantingSiteId =
        insertPlantingSite(
            boundary = multiPolygon(3.0), exclusion = multiPolygon(1.5), timeZone = timeZone)
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
        PlantingSiteModel(
            boundary = multiPolygon(3.0),
            description = null,
            exclusion = multiPolygon(1.5),
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
                        errorMargin = PlantingSiteImporter.DEFAULT_ERROR_MARGIN,
                        extraPermanentClusters = 1,
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
                                                    isAvailable = true,
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
    val exclusion4326 = multiPolygon(5.0)

    val siteBoundary3857 = siteBoundary4326.to3857()
    val zoneBoundary3857 = zoneBoundary4326.to3857()
    val subzoneBoundary3857 = subzoneBoundary4326.to3857()
    val monitoringPlotBoundary3857 = monitoringPlotBoundary4326.to3857()
    val exclusion3857 = exclusion4326.to3857()

    val plantingSiteId = insertPlantingSite(boundary = siteBoundary3857, exclusion = exclusion3857)
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
                        errorMargin = PlantingSiteImporter.DEFAULT_ERROR_MARGIN,
                        extraPermanentClusters = 0,
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
                                                isAvailable = true,
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
                  projectId = projectId,
                  timeZone = timeZone,
              )),
          plantingSitesDao.findAll(),
          "Planting sites")

      assertEquals(emptyList<PlantingZonesRow>(), plantingZonesDao.findAll(), "Planting zones")
    }

    @Test
    fun `inserts initial planting seasons`() {
      clock.instant = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, timeZone).toInstant()

      val season1StartDate = LocalDate.of(2022, 12, 1)
      val season1EndDate = LocalDate.of(2023, 2, 1)
      val season2StartDate = LocalDate.of(2024, 1, 1)
      val season2EndDate = LocalDate.of(2024, 3, 1)

      val model =
          store.createPlantingSite(
              description = null,
              name = "name",
              organizationId = organizationId,
              plantingSeasons =
                  listOf(
                      UpdatedPlantingSeasonModel(
                          startDate = season1StartDate, endDate = season1EndDate),
                      UpdatedPlantingSeasonModel(
                          startDate = season2StartDate, endDate = season2EndDate),
                  ),
              projectId = null,
              timeZone = timeZone,
          )

      val expected =
          listOf(
              PlantingSeasonsRow(
                  endDate = season1EndDate,
                  endTime = season1EndDate.plusDays(1).toInstant(timeZone),
                  isActive = true,
                  plantingSiteId = model.id,
                  startDate = season1StartDate,
                  startTime = season1StartDate.toInstant(timeZone),
              ),
              PlantingSeasonsRow(
                  endDate = season2EndDate,
                  endTime = season2EndDate.plusDays(1).toInstant(timeZone),
                  isActive = false,
                  plantingSiteId = model.id,
                  startDate = season2StartDate,
                  startTime = season2StartDate.toInstant(timeZone),
              ),
          )

      val actual = plantingSeasonsDao.findAll().map { it.copy(id = null) }.sortedBy { it.startDate }

      assertEquals(expected, actual)
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

    @Test
    fun `rejects overlapping planting seasons`() {
      clock.instant = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

      assertThrows<PlantingSeasonsOverlapException> {
        store.createPlantingSite(
            description = null,
            organizationId = organizationId,
            name = "name",
            projectId = null,
            timeZone = timeZone,
            plantingSeasons =
                listOf(
                    UpdatedPlantingSeasonModel(
                        startDate = LocalDate.of(2023, 1, 1), endDate = LocalDate.of(2023, 2, 15)),
                    UpdatedPlantingSeasonModel(
                        startDate = LocalDate.of(2023, 2, 10), endDate = LocalDate.of(2023, 5, 1))))
      }
    }

    @Test
    fun `rejects invalid planting seasons`() {
      clock.instant = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

      assertPlantingSeasonCreateThrows<CannotCreatePastPlantingSeasonException>(
          LocalDate.of(2022, 1, 1), LocalDate.of(2022, 6, 1))
      assertPlantingSeasonCreateThrows<PlantingSeasonTooFarInFutureException>(
          LocalDate.of(2025, 1, 1), LocalDate.of(2025, 3, 1))
      assertPlantingSeasonCreateThrows<PlantingSeasonTooLongException>(
          LocalDate.of(2023, 1, 1), LocalDate.of(2024, 1, 2))
      assertPlantingSeasonCreateThrows<PlantingSeasonTooShortException>(
          LocalDate.of(2023, 1, 1), LocalDate.of(2023, 1, 15))
    }

    private inline fun <reified T : Exception> assertPlantingSeasonCreateThrows(
        startDate: LocalDate,
        endDate: LocalDate
    ) {
      assertThrows<T> {
        store.createPlantingSite(
            description = null,
            organizationId = organizationId,
            name = "name",
            projectId = null,
            timeZone = timeZone,
            plantingSeasons =
                listOf(UpdatedPlantingSeasonModel(startDate = startDate, endDate = endDate)))
      }
    }
  }

  @Nested
  inner class UpdatePlantingSite {
    @BeforeEach
    fun setUp() {
      clock.instant = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()
    }

    @Test
    fun `updates values`() {
      val initialModel =
          store.createPlantingSite(
              boundary = multiPolygon(1.0),
              description = null,
              name = "initial name",
              organizationId = organizationId,
              plantingSeasons = emptyList(),
              projectId = null,
              timeZone = timeZone,
          )

      val createdTime = clock.instant()
      val newTimeZone = insertTimeZone("Europe/Paris")
      val now = createdTime.plusSeconds(1000)
      clock.instant = now

      store.updatePlantingSite(initialModel.id, emptyList()) { model ->
        model.copy(
            boundary = multiPolygon(2.0),
            description = "new description",
            name = "new name",
            timeZone = newTimeZone,
        )
      }

      assertEquals(
          listOf(
              PlantingSitesRow(
                  boundary = multiPolygon(2.0),
                  id = initialModel.id,
                  organizationId = organizationId,
                  name = "new name",
                  description = "new description",
                  createdBy = user.userId,
                  createdTime = createdTime,
                  modifiedBy = user.userId,
                  modifiedTime = now,
                  timeZone = newTimeZone,
              )),
          plantingSitesDao.findAll(),
          "Planting sites")
    }

    @Test
    fun `updates planting seasons`() {
      val season1Start = LocalDate.of(2023, 1, 1)
      val season1End = LocalDate.of(2023, 2, 15)
      val oldSeason2Start = LocalDate.of(2023, 3, 1)
      val newSeason2Start = LocalDate.of(2023, 3, 2)
      val oldSeason2End = LocalDate.of(2023, 4, 15)
      val newSeason2End = LocalDate.of(2023, 4, 16)
      val season3Start = LocalDate.of(2023, 5, 1)
      val season3End = LocalDate.of(2023, 6, 15)
      val season4Start = LocalDate.of(2023, 5, 5)
      val season4End = LocalDate.of(2023, 6, 10)

      val plantingSiteId = insertPlantingSite(timeZone = timeZone)
      val season1Id =
          insertPlantingSeason(startDate = season1Start, endDate = season1End, timeZone = timeZone)
      val season2Id =
          insertPlantingSeason(
              startDate = oldSeason2Start, endDate = oldSeason2End, timeZone = timeZone)
      insertPlantingSeason(startDate = season3Start, endDate = season3End, timeZone = timeZone)

      val desiredSeasons =
          listOf(
              // Unchanged
              UpdatedPlantingSeasonModel(
                  startDate = season1Start, endDate = season1End, id = season1Id),
              // Rescheduled (same ID, different dates)
              UpdatedPlantingSeasonModel(
                  startDate = newSeason2Start, endDate = newSeason2End, id = season2Id),
              // New
              UpdatedPlantingSeasonModel(startDate = season4Start, endDate = season4End),
          )

      store.updatePlantingSite(plantingSiteId, desiredSeasons) { it }

      val actual = plantingSeasonsDao.findAll().sortedBy { it.startDate!! }

      val expected =
          listOf(
              PlantingSeasonsRow(
                  endDate = season1End,
                  endTime = season1End.plusDays(1).toInstant(timeZone),
                  id = season1Id,
                  isActive = false,
                  plantingSiteId = plantingSiteId,
                  startDate = season1Start,
                  startTime = season1Start.toInstant(timeZone),
              ),
              PlantingSeasonsRow(
                  endDate = newSeason2End,
                  endTime = newSeason2End.plusDays(1).toInstant(timeZone),
                  id = season2Id,
                  isActive = false,
                  plantingSiteId = plantingSiteId,
                  startDate = newSeason2Start,
                  startTime = newSeason2Start.toInstant(timeZone),
              ),
              PlantingSeasonsRow(
                  endDate = season4End,
                  endTime = season4End.plusDays(1).toInstant(timeZone),
                  id = actual.last().id,
                  isActive = false,
                  plantingSiteId = plantingSiteId,
                  startDate = season4Start,
                  startTime = season4Start.toInstant(timeZone),
              ),
          )

      assertEquals(expected, actual)
    }

    @Test
    fun `marks planting season as inactive if it is rescheduled for the future after starting`() {
      val plantingSiteId = insertPlantingSite()
      val seasonId =
          insertPlantingSeason(
              startDate = LocalDate.of(2022, 12, 1),
              endDate = LocalDate.of(2023, 2, 1),
              isActive = true)

      val newStartDate = LocalDate.of(2023, 2, 1)
      val newEndDate = LocalDate.of(2023, 4, 1)
      val desiredSeasons =
          listOf(
              UpdatedPlantingSeasonModel(
                  startDate = newStartDate, endDate = newEndDate, id = seasonId))

      store.updatePlantingSite(plantingSiteId, desiredSeasons) { it }

      val expected =
          listOf(
              PlantingSeasonsRow(
                  endDate = newEndDate,
                  endTime = newEndDate.plusDays(1).toInstant(ZoneOffset.UTC),
                  id = seasonId,
                  isActive = false,
                  plantingSiteId = plantingSiteId,
                  startDate = newStartDate,
                  startTime = newStartDate.toInstant(ZoneOffset.UTC),
              ),
          )

      assertEquals(expected, plantingSeasonsDao.findAll())
    }

    @Test
    fun `marks planting season as active if it is rescheduled to start in the past`() {
      val plantingSiteId = insertPlantingSite()
      val seasonId =
          insertPlantingSeason(
              startDate = LocalDate.of(2023, 2, 1), endDate = LocalDate.of(2023, 4, 1))

      val newStartDate = LocalDate.of(2022, 12, 1)
      val newEndDate = LocalDate.of(2023, 2, 1)
      val desiredSeasons =
          listOf(
              UpdatedPlantingSeasonModel(
                  startDate = newStartDate, endDate = newEndDate, id = seasonId))

      store.updatePlantingSite(plantingSiteId, desiredSeasons) { it }

      val expected =
          listOf(
              PlantingSeasonsRow(
                  endDate = newEndDate,
                  endTime = newEndDate.plusDays(1).toInstant(ZoneOffset.UTC),
                  id = seasonId,
                  isActive = true,
                  plantingSiteId = plantingSiteId,
                  startDate = newStartDate,
                  startTime = newStartDate.toInstant(ZoneOffset.UTC),
              ),
          )

      assertEquals(expected, plantingSeasonsDao.findAll())
    }

    @Test
    fun `rejects invalid planting seasons`() {
      insertPlantingSite(timeZone = timeZone)

      // Planting site time zone is Honolulu which is GMT-10, meaning it is 2022-12-31 there;
      // 2024-01-01 is thus more than a year in the future.
      assertPlantingSeasonUpdateThrows<PlantingSeasonTooFarInFutureException>(
          LocalDate.of(2024, 1, 1), LocalDate.of(2024, 2, 1))

      assertPlantingSeasonUpdateThrows<CannotCreatePastPlantingSeasonException>(
          LocalDate.of(2022, 1, 1), LocalDate.of(2022, 6, 1))
      assertPlantingSeasonUpdateThrows<PlantingSeasonTooShortException>(
          LocalDate.of(2023, 1, 1), LocalDate.of(2023, 1, 27))
      assertPlantingSeasonUpdateThrows<PlantingSeasonTooLongException>(
          LocalDate.of(2023, 1, 1), LocalDate.of(2024, 1, 2))
    }

    @Test
    fun `rejects overlapping planting seasons`() {
      val plantingSiteId = insertPlantingSite()

      assertThrows<PlantingSeasonsOverlapException> {
        store.updatePlantingSite(
            plantingSiteId,
            listOf(
                UpdatedPlantingSeasonModel(
                    startDate = LocalDate.of(2023, 1, 1), endDate = LocalDate.of(2023, 2, 1)),
                UpdatedPlantingSeasonModel(
                    startDate = LocalDate.of(2023, 2, 1), endDate = LocalDate.of(2023, 4, 1)),
            )) {
              it
            }
      }
    }

    @Test
    fun `rejects updates of past planting seasons`() {
      val startDate = LocalDate.of(2022, 11, 1)
      val endDate = LocalDate.of(2022, 12, 15)

      val plantingSiteId = insertPlantingSite()
      val plantingSeasonId = insertPlantingSeason(startDate = startDate, endDate = endDate)

      assertThrows<CannotUpdatePastPlantingSeasonException> {
        store.updatePlantingSite(
            plantingSiteId,
            listOf(
                UpdatedPlantingSeasonModel(
                    startDate = startDate,
                    endDate = LocalDate.of(2023, 1, 15),
                    id = plantingSeasonId))) {
              it
            }
      }
    }

    @Test
    fun `ignores existing past planting seasons`() {
      val startDate = LocalDate.of(2020, 1, 1)
      val endDate = LocalDate.of(2020, 3, 1)

      val plantingSiteId = insertPlantingSite()
      val plantingSeasonId = insertPlantingSeason(startDate = startDate, endDate = endDate)

      val expected = plantingSeasonsDao.findAll()

      store.updatePlantingSite(
          plantingSiteId,
          listOf(
              UpdatedPlantingSeasonModel(
                  startDate = startDate, endDate = endDate, id = plantingSeasonId))) {
            it
          }

      val actual = plantingSeasonsDao.findAll()

      assertEquals(expected, actual)
    }

    @Test
    fun `ignores deletion of past planting seasons`() {
      val startDate = LocalDate.of(2020, 1, 1)
      val endDate = LocalDate.of(2020, 3, 1)

      val plantingSiteId = insertPlantingSite()
      insertPlantingSeason(startDate = startDate, endDate = endDate)

      val expected = plantingSeasonsDao.findAll()

      store.updatePlantingSite(plantingSiteId, emptyList()) { it }

      assertEquals(expected, plantingSeasonsDao.findAll())
    }

    @Test
    fun `updates planting season start and end times when time zone changes`() {
      insertTimeZone(ZoneOffset.UTC)
      val plantingSiteId = insertPlantingSite(timeZone = ZoneOffset.UTC)

      val pastStartDate = LocalDate.of(2020, 1, 1)
      val pastEndDate = LocalDate.of(2020, 3, 1)
      val pastPlantingSeasonId =
          insertPlantingSeason(startDate = pastStartDate, endDate = pastEndDate)
      val activeStartDate = LocalDate.of(2022, 12, 1)
      val activeEndDate = LocalDate.of(2022, 12, 31)
      val activePlantingSeasonId =
          insertPlantingSeason(startDate = activeStartDate, endDate = activeEndDate)
      val futureStartDate = LocalDate.of(2023, 6, 1)
      val futureEndDate = LocalDate.of(2023, 8, 15)
      val futurePlantingSeasonId =
          insertPlantingSeason(startDate = futureStartDate, endDate = futureEndDate)

      store.updatePlantingSite(
          plantingSiteId,
          listOf(
              UpdatedPlantingSeasonModel(
                  startDate = pastStartDate, endDate = pastEndDate, id = pastPlantingSeasonId),
              UpdatedPlantingSeasonModel(
                  startDate = activeStartDate,
                  endDate = activeEndDate,
                  id = activePlantingSeasonId),
              UpdatedPlantingSeasonModel(
                  startDate = futureStartDate,
                  endDate = futureEndDate,
                  id = futurePlantingSeasonId),
          )) {
            it.copy(timeZone = timeZone)
          }

      val expected =
          listOf(
              PlantingSeasonsRow(
                  endDate = pastEndDate,
                  endTime = pastEndDate.plusDays(1).toInstant(ZoneOffset.UTC),
                  id = pastPlantingSeasonId,
                  isActive = false,
                  plantingSiteId = plantingSiteId,
                  startDate = pastStartDate,
                  startTime = pastStartDate.toInstant(ZoneOffset.UTC),
              ),
              PlantingSeasonsRow(
                  endDate = activeEndDate,
                  endTime = activeEndDate.plusDays(1).toInstant(timeZone),
                  id = activePlantingSeasonId,
                  // New time zone means it is now December 31, not January 1, so this planting
                  // season ending on December 31 has become active.
                  isActive = true,
                  plantingSiteId = plantingSiteId,
                  startDate = activeStartDate,
                  // Start date didn't change and start time is in the past, so it shouldn't be
                  // updated.
                  startTime = activeStartDate.toInstant(ZoneOffset.UTC),
              ),
              PlantingSeasonsRow(
                  endDate = futureEndDate,
                  endTime = futureEndDate.plusDays(1).toInstant(timeZone),
                  id = futurePlantingSeasonId,
                  isActive = false,
                  plantingSiteId = plantingSiteId,
                  startDate = futureStartDate,
                  startTime = futureStartDate.toInstant(timeZone),
              ),
          )

      assertEquals(expected, plantingSeasonsDao.findAll().sortedBy { it.startDate })
    }

    @Test
    fun `publishes event when planting season is added`() {
      val plantingSiteId = insertPlantingSite()
      val startDate = LocalDate.of(2023, 1, 2)
      val endDate = LocalDate.of(2023, 2, 15)

      store.updatePlantingSite(
          plantingSiteId,
          listOf(
              UpdatedPlantingSeasonModel(startDate = startDate, endDate = endDate),
          )) {
            it
          }

      val plantingSeasonId = plantingSeasonsDao.findAll().first().id!!

      eventPublisher.assertEventPublished(
          PlantingSeasonScheduledEvent(plantingSiteId, plantingSeasonId, startDate, endDate))
    }

    @Test
    fun `publishes event when planting season is modified`() {
      val plantingSiteId = insertPlantingSite()
      val oldStartDate = LocalDate.of(2023, 1, 1)
      val oldEndDate = LocalDate.of(2023, 1, 31)
      val newStartDate = LocalDate.of(2023, 1, 2)
      val newEndDate = LocalDate.of(2023, 2, 15)

      val plantingSeasonId = insertPlantingSeason(startDate = oldStartDate, endDate = oldEndDate)

      store.updatePlantingSite(
          plantingSiteId,
          listOf(
              UpdatedPlantingSeasonModel(
                  startDate = newStartDate, endDate = newEndDate, id = plantingSeasonId),
          )) {
            it
          }

      eventPublisher.assertEventPublished(
          PlantingSeasonRescheduledEvent(
              plantingSiteId, plantingSeasonId, oldStartDate, oldEndDate, newStartDate, newEndDate))
    }

    @Test
    fun `ignores boundary updates on detailed planting sites`() {
      val plantingSiteId = insertPlantingSite(boundary = multiPolygon(1.0))
      insertPlantingZone()

      store.updatePlantingSite(plantingSiteId, emptyList()) { model ->
        model.copy(boundary = multiPolygon(2.0))
      }

      assertEquals(multiPolygon(1.0), plantingSitesDao.findAll().first().boundary)
    }

    @Test
    fun `publishes event if time zone updated`() {
      val plantingSiteId = insertPlantingSite(timeZone = timeZone)
      val initialModel = store.fetchSiteById(plantingSiteId, PlantingSiteDepth.Site)
      val newTimeZone = insertTimeZone("Europe/Paris")

      store.updatePlantingSite(plantingSiteId, emptyList()) { it.copy(timeZone = newTimeZone) }

      val expectedEvent =
          PlantingSiteTimeZoneChangedEvent(
              initialModel.copy(timeZone = newTimeZone), timeZone, newTimeZone)

      eventPublisher.assertEventPublished(expectedEvent)
    }

    @Test
    fun `does not publish event if time zone not updated`() {
      val plantingSiteId = insertPlantingSite(timeZone = timeZone)

      store.updatePlantingSite(plantingSiteId, emptyList()) { it.copy(description = "edited") }

      eventPublisher.assertEventNotPublished(PlantingSiteTimeZoneChangedEvent::class.java)
    }

    @Test
    fun `throws exception if no permission`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canUpdatePlantingSite(any()) } returns false

      assertThrows<AccessDeniedException> {
        store.updatePlantingSite(plantingSiteId, emptyList()) { it }
      }
    }

    @Test
    fun `throws exception if project is in a different organization`() {
      val plantingSiteId = insertPlantingSite()
      val otherOrganizationId = OrganizationId(2)
      insertOrganization(otherOrganizationId)
      val otherOrgProjectId = insertProject(organizationId = otherOrganizationId)

      assertThrows<ProjectInDifferentOrganizationException> {
        store.updatePlantingSite(plantingSiteId, emptyList()) {
          it.copy(projectId = otherOrgProjectId)
        }
      }
    }

    private inline fun <reified T : Exception> assertPlantingSeasonUpdateThrows(
        startDate: LocalDate,
        endDate: LocalDate
    ) {
      assertThrows<T> {
        store.updatePlantingSite(
            inserted.plantingSiteId,
            plantingSeasons =
                listOf(UpdatedPlantingSeasonModel(startDate = startDate, endDate = endDate))) {
              it
            }
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
    val createdBy = insertUser(100)
    val plantingSiteId = insertPlantingSite()

    val initialRow =
        PlantingZonesRow(
            areaHa = BigDecimal.ONE,
            boundary = multiPolygon(1.0),
            createdBy = createdBy,
            createdTime = createdTime,
            errorMargin = BigDecimal.TWO,
            extraPermanentClusters = 0,
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
    val newExtraPermanent = 15
    val newTargetPlantingDensity = BigDecimal(13)

    val expected =
        initialRow.copy(
            errorMargin = newErrorMargin,
            extraPermanentClusters = newExtraPermanent,
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
          extraPermanentClusters = newExtraPermanent,
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
  inner class MarkSchedulingObservationsNotificationComplete {
    @Test
    fun `records notification sent time`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canReadPlantingSite(plantingSiteId) } returns true
      every { user.canManageNotifications() } returns true

      clock.instant = Instant.ofEpochSecond(1234)

      store.markNotificationComplete(plantingSiteId, NotificationType.ScheduleObservation, 1)

      assertEquals(clock.instant, plantingSiteNotificationsDao.findAll().single().sentTime)
    }

    @Test
    fun `throws exception if no permission to manage notifications`() {
      val plantingSiteId = insertPlantingSite()

      every { user.canReadPlantingSite(plantingSiteId) } returns true
      every { user.canManageNotifications() } returns false

      assertThrows<AccessDeniedException> {
        store.markNotificationComplete(plantingSiteId, NotificationType.ScheduleObservation, 1)
      }
    }
  }

  @Nested
  inner class HasSubzonePlantings {
    private val plantingSiteId = PlantingSiteId(1)

    @BeforeEach
    fun setUp() {
      every { user.canReadPlantingSite(plantingSiteId) } returns true
    }

    @Test
    fun `throws exception when no permission to read the planting site`() {
      insertPlantingSite(id = plantingSiteId)

      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> { store.hasSubzonePlantings(plantingSiteId) }
    }

    @Test
    fun `returns false when there are no plantings in subzones for a site without subzones`() {
      insertPlantingSite(id = plantingSiteId)

      assertFalse(store.hasSubzonePlantings(plantingSiteId))
    }

    @Test
    fun `returns false when there are no plantings in subzones for a site with subzones`() {
      insertPlantingSite(id = plantingSiteId)
      insertPlantingZone()
      insertPlantingSubzone()

      assertFalse(store.hasSubzonePlantings(plantingSiteId))
    }

    @Test
    fun `returns true when there are plantings in subzones`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite(id = plantingSiteId)
      insertPlantingZone()
      insertPlantingSubzone()
      insertWithdrawal()
      insertDelivery()
      insertPlanting()

      assertTrue(store.hasSubzonePlantings(plantingSiteId))
    }
  }

  @Nested
  inner class HasPlantings {
    private val plantingSiteId = PlantingSiteId(1)

    @BeforeEach
    fun setUp() {
      every { user.canReadPlantingSite(plantingSiteId) } returns true
    }

    @Test
    fun `throws exception when no permission to read the planting site`() {
      insertPlantingSite(id = plantingSiteId)

      every { user.canReadPlantingSite(plantingSiteId) } returns false

      assertThrows<PlantingSiteNotFoundException> { store.hasPlantings(plantingSiteId) }
    }

    @Test
    fun `returns false when there are no plantings in the site`() {
      insertPlantingSite(id = plantingSiteId)

      assertFalse(store.hasPlantings(plantingSiteId))
    }

    @Test
    fun `returns true when there are plantings in the site`() {
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertPlantingSite(id = plantingSiteId)
      insertWithdrawal()
      insertDelivery()
      insertPlanting()

      assertTrue(store.hasPlantings(plantingSiteId))
    }
  }

  @Nested
  inner class DeletePlantingSite {
    @Test
    fun `deletes detailed map data and observations`() {
      every { user.canDeletePlantingSite(any()) } returns true

      val plantingSiteId = insertPlantingSite()
      insertPlantingZone()
      insertPlantingSubzone()
      insertMonitoringPlot()
      insertFacility(type = FacilityType.Nursery)
      insertSpecies()
      insertWithdrawal()
      insertDelivery()
      insertPlanting()
      insertObservation(completedTime = Instant.EPOCH)
      insertObservationPlot()
      insertObservedCoordinates()
      insertRecordedPlant()

      store.deletePlantingSite(plantingSiteId)

      fun assertAllDeleted(dao: DAO<*, *, *>) {
        assertEquals(emptyList<Any>(), dao.findAll())
      }

      assertAllDeleted(plantingSitesDao)
      assertAllDeleted(plantingZonesDao)
      assertAllDeleted(plantingSubzonesDao)
      assertAllDeleted(monitoringPlotsDao)
      assertAllDeleted(deliveriesDao)
      assertAllDeleted(plantingsDao)
      assertAllDeleted(observationsDao)
      assertAllDeleted(observationPlotsDao)
      assertAllDeleted(observedPlotCoordinatesDao)
      assertAllDeleted(recordedPlantsDao)

      eventPublisher.assertEventPublished(PlantingSiteDeletionStartedEvent(plantingSiteId))
    }

    @Test
    fun `throws exception if no permission to delete planting site`() {
      every { user.canDeletePlantingSite(any()) } returns false

      val plantingSiteId = insertPlantingSite()

      assertThrows<AccessDeniedException> { store.deletePlantingSite(plantingSiteId) }
    }
  }

  @Nested
  inner class TransitionPlantingSeasons {
    @Test
    fun `marks planting season as active when its start time arrives`() {
      val startDate = LocalDate.EPOCH.plusDays(1)
      val endDate = startDate.plusDays(60)
      val model =
          store.createPlantingSite(
              description = null,
              name = "name",
              organizationId = organizationId,
              plantingSeasons =
                  listOf(UpdatedPlantingSeasonModel(startDate = startDate, endDate = endDate)),
              projectId = null,
              timeZone = timeZone,
          )

      assertSeasonActive(false, "Should start as inactive")

      store.transitionPlantingSeasons()

      assertSeasonActive(false, "Should stay inactive until start time arrives")
      eventPublisher.assertEventNotPublished<PlantingSeasonStartedEvent>()

      clock.instant = startDate.toInstant(timeZone)

      store.transitionPlantingSeasons()

      assertSeasonActive(true, "Should transition to active")
      eventPublisher.assertEventPublished(
          PlantingSeasonStartedEvent(model.id, model.plantingSeasons.first().id))

      clock.instant = endDate.minusDays(1).toInstant(timeZone)
      eventPublisher.clear()

      store.transitionPlantingSeasons()
      eventPublisher.assertNoEventsPublished(
          "Should not publish any events if planting season already in correct state")
    }

    @Test
    fun `marks planting season as inactive when its end time arrives`() {
      val startDate = LocalDate.EPOCH.plusDays(1)
      val endDate = startDate.plusDays(60)

      clock.instant = startDate.toInstant(timeZone)

      val model =
          store.createPlantingSite(
              description = null,
              name = "name",
              organizationId = organizationId,
              plantingSeasons =
                  listOf(UpdatedPlantingSeasonModel(startDate = startDate, endDate = endDate)),
              projectId = null,
              timeZone = timeZone,
          )

      assertSeasonActive(true, "Should start as active")

      store.transitionPlantingSeasons()

      assertSeasonActive(true, "Should remain active until end time arrives")
      eventPublisher.assertEventNotPublished<PlantingSeasonEndedEvent>()

      clock.instant = endDate.plusDays(1).toInstant(timeZone)

      store.transitionPlantingSeasons()

      assertSeasonActive(false, "Should have been marked as inactive")
      eventPublisher.assertEventPublished(
          PlantingSeasonEndedEvent(model.id, model.plantingSeasons.first().id))

      clock.instant = endDate.plusDays(2).toInstant(timeZone)
      eventPublisher.clear()

      store.transitionPlantingSeasons()
      eventPublisher.assertNoEventsPublished(
          "Should not publish any events if planting season already in correct state")
    }

    private fun assertSeasonActive(isActive: Boolean, message: String) {
      assertEquals(listOf(isActive), plantingSeasonsDao.findAll().map { it.isActive }, message)
    }
  }
}
