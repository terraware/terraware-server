package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.ProjectInDifferentOrganizationException
import com.terraformation.backend.db.StableId
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSeasonsRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSiteHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.PlantingSitesRow
import com.terraformation.backend.db.tracking.tables.pojos.StrataRow
import com.terraformation.backend.db.tracking.tables.pojos.StratumHistoriesRow
import com.terraformation.backend.db.tracking.tables.pojos.SubstrataRow
import com.terraformation.backend.db.tracking.tables.pojos.SubstratumHistoriesRow
import com.terraformation.backend.db.tracking.tables.references.MONITORING_PLOT_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITE_HISTORIES
import com.terraformation.backend.db.tracking.tables.references.STRATA
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.PlantingSiteMapInvalidException
import com.terraformation.backend.tracking.model.CannotCreatePastPlantingSeasonException
import com.terraformation.backend.tracking.model.PlantingSeasonTooFarInFutureException
import com.terraformation.backend.tracking.model.PlantingSeasonTooLongException
import com.terraformation.backend.tracking.model.PlantingSeasonTooShortException
import com.terraformation.backend.tracking.model.PlantingSeasonsOverlapException
import com.terraformation.backend.tracking.model.PlantingSiteModel
import com.terraformation.backend.tracking.model.StratumModel
import com.terraformation.backend.tracking.model.SubstratumModel
import com.terraformation.backend.tracking.model.UpdatedPlantingSeasonModel
import com.terraformation.backend.util.Turtle
import com.terraformation.backend.util.toInstant
import io.mockk.every
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

internal class PlantingSiteStoreCreateSiteTest : BasePlantingSiteStoreTest() {
  @Nested
  inner class CreatePlantingSite {
    @Test
    fun `inserts new site`() {
      val projectId = insertProject()
      val model =
          store.createPlantingSite(
              PlantingSiteModel.create(
                  description = "description",
                  name = "name",
                  organizationId = organizationId,
                  projectId = projectId,
                  timeZone = timeZone,
              )
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
                  survivalRateIncludesTempPlots = false,
                  timeZone = timeZone,
              )
          ),
          plantingSitesDao.findAll(),
          "Planting sites",
      )

      assertTableEmpty(PLANTING_SITE_HISTORIES)
      assertTableEmpty(STRATA)
    }

    @Test
    fun `calculates correct area and grid origin for simple site with boundary`() {
      val gridOrigin = point(1)
      val boundary = Turtle(gridOrigin).makeMultiPolygon { square(150) }

      val model =
          store.createPlantingSite(
              PlantingSiteModel.create(
                  boundary = boundary,
                  name = "name",
                  organizationId = organizationId,
              )
          )

      assertEquals(
          listOf(
              PlantingSitesRow(
                  areaHa = BigDecimal("2.3"),
                  boundary = boundary,
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  gridOrigin = gridOrigin,
                  id = model.id,
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
                  name = "name",
                  organizationId = organizationId,
                  survivalRateIncludesTempPlots = false,
              )
          ),
          plantingSitesDao.findAll(),
          "Planting sites",
      )

      assertTableEmpty(STRATA)
    }

    @Test
    fun `does not store area for simple site with tiny boundary`() {
      val boundary = Turtle(point(1)).makeMultiPolygon { square(5) }

      val model =
          store.createPlantingSite(
              PlantingSiteModel.create(
                  boundary = boundary,
                  name = "name",
                  organizationId = organizationId,
              )
          )

      assertNull(plantingSitesDao.fetchOneById(model.id)!!.areaHa, "Planting site area")
    }

    @Test
    fun `inserts detailed planting site`() {
      val gridOrigin = point(0.0, 51.0) // Southeastern Great Britain
      val siteBoundary = Turtle(gridOrigin).makeMultiPolygon { rectangle(200, 150) }
      val stratum1Boundary = Turtle(gridOrigin).makeMultiPolygon { rectangle(100, 150) }
      val substratum11Boundary = Turtle(gridOrigin).makeMultiPolygon { rectangle(100, 75) }
      val substratum12Boundary =
          Turtle(gridOrigin).makeMultiPolygon {
            north(75)
            rectangle(100, 75)
          }
      val stratum2Boundary =
          Turtle(gridOrigin).makeMultiPolygon {
            east(100)
            rectangle(100, 150)
          }
      val substratum21Boundary =
          Turtle(gridOrigin).makeMultiPolygon {
            east(100)
            rectangle(100, 75)
          }
      val substratum22Boundary =
          Turtle(gridOrigin).makeMultiPolygon {
            east(100)
            north(75)
            rectangle(100, 75)
          }

      val newModel =
          PlantingSiteModel.create(
              boundary = siteBoundary,
              name = "name",
              organizationId = organizationId,
              strata =
                  listOf(
                      StratumModel.create(
                          boundary = stratum1Boundary,
                          errorMargin = BigDecimal(1),
                          name = "Stratum 1",
                          numPermanentPlots = 3,
                          numTemporaryPlots = 4,
                          substrata =
                              listOf(
                                  SubstratumModel.create(
                                      boundary = substratum11Boundary,
                                      fullName = "Stratum 1-Substratum 1",
                                      name = "Substratum 1",
                                  ),
                                  SubstratumModel.create(
                                      boundary = substratum12Boundary,
                                      fullName = "Stratum 1-Substratum 2",
                                      name = "Substratum 2",
                                  ),
                              ),
                          studentsT = BigDecimal(5),
                          targetPlantingDensity = BigDecimal(6),
                          variance = BigDecimal(7),
                      ),
                      StratumModel.create(
                          boundary = stratum2Boundary,
                          name = "Stratum 2",
                          substrata =
                              listOf(
                                  SubstratumModel.create(
                                      boundary = substratum21Boundary,
                                      fullName = "Stratum 2-Substratum 1",
                                      name = "Substratum 1",
                                  ),
                                  SubstratumModel.create(
                                      boundary = substratum22Boundary,
                                      fullName = "Stratum 2-Substratum 2",
                                      name = "Substratum 2",
                                  ),
                              ),
                      ),
                  ),
          )

      val model = store.createPlantingSite(newModel)

      val plantingSitesRow = plantingSitesDao.findAll().single()

      assertGeometryEquals(siteBoundary, plantingSitesRow.boundary, "Planting site boundary")
      assertGeometryEquals(gridOrigin, plantingSitesRow.gridOrigin, "Planting site grid origin")
      assertEquals(
          PlantingSitesRow(
              areaHa = BigDecimal("3.0"),
              countryCode = "GB",
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              id = model.id,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              name = "name",
              organizationId = organizationId,
              survivalRateIncludesTempPlots = false,
          ),
          plantingSitesRow.copy(boundary = null, gridOrigin = null),
          "Planting site",
      )

      val commonStrataRow =
          StrataRow(
              areaHa = BigDecimal("1.5"),
              boundaryModifiedBy = user.userId,
              boundaryModifiedTime = Instant.EPOCH,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              plantingSiteId = model.id,
          )

      val actualStrata = strataDao.findAll().associateBy { it.name!! }

      assertGeometryEquals(
          stratum1Boundary,
          actualStrata["Stratum 1"]?.boundary,
          "Stratum 1 boundary",
      )
      assertGeometryEquals(
          stratum2Boundary,
          actualStrata["Stratum 2"]?.boundary,
          "Stratum 2 boundary",
      )
      assertSetEquals(
          setOf(
              commonStrataRow.copy(
                  errorMargin = BigDecimal(1),
                  id = actualStrata["Stratum 1"]?.id,
                  name = "Stratum 1",
                  numPermanentPlots = 3,
                  numTemporaryPlots = 4,
                  stableId = StableId("Stratum 1"),
                  studentsT = BigDecimal(5),
                  targetPlantingDensity = BigDecimal(6),
                  variance = BigDecimal(7),
              ),
              commonStrataRow.copy(
                  errorMargin = StratumModel.DEFAULT_ERROR_MARGIN,
                  id = actualStrata["Stratum 2"]?.id,
                  name = "Stratum 2",
                  numPermanentPlots = StratumModel.DEFAULT_NUM_PERMANENT_PLOTS,
                  numTemporaryPlots = StratumModel.DEFAULT_NUM_TEMPORARY_PLOTS,
                  stableId = StableId("Stratum 2"),
                  studentsT = StratumModel.DEFAULT_STUDENTS_T,
                  targetPlantingDensity = StratumModel.DEFAULT_TARGET_PLANTING_DENSITY,
                  variance = StratumModel.DEFAULT_VARIANCE,
              ),
          ),
          actualStrata.values.map { it.copy(boundary = null) }.toSet(),
          "Strata",
      )

      val commonSubstrataRow =
          SubstrataRow(
              areaHa = BigDecimal("0.8"),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              plantingSiteId = model.id,
          )

      val actualSubstrata = substrataDao.findAll().map { it.copy(id = null) }

      assertGeometryEquals(
          substratum11Boundary,
          actualSubstrata.single { it.fullName == "Stratum 1-Substratum 1" }.boundary,
          "Z1S1 boundary",
      )
      assertGeometryEquals(
          substratum12Boundary,
          actualSubstrata.single { it.fullName == "Stratum 1-Substratum 2" }.boundary,
          "Z1S2 boundary",
      )
      assertGeometryEquals(
          substratum21Boundary,
          actualSubstrata.single { it.fullName == "Stratum 2-Substratum 1" }.boundary,
          "Z2S1 boundary",
      )
      assertGeometryEquals(
          substratum22Boundary,
          actualSubstrata.single { it.fullName == "Stratum 2-Substratum 2" }.boundary,
          "Z2S2 boundary",
      )
      assertSetEquals(
          setOf(
              commonSubstrataRow.copy(
                  fullName = "Stratum 1-Substratum 1",
                  name = "Substratum 1",
                  stratumId = actualStrata["Stratum 1"]?.id,
                  stableId = StableId("Stratum 1-Substratum 1"),
              ),
              commonSubstrataRow.copy(
                  fullName = "Stratum 1-Substratum 2",
                  name = "Substratum 2",
                  stratumId = actualStrata["Stratum 1"]?.id,
                  stableId = StableId("Stratum 1-Substratum 2"),
              ),
              commonSubstrataRow.copy(
                  fullName = "Stratum 2-Substratum 1",
                  name = "Substratum 1",
                  stratumId = actualStrata["Stratum 2"]?.id,
                  stableId = StableId("Stratum 2-Substratum 1"),
              ),
              commonSubstrataRow.copy(
                  fullName = "Stratum 2-Substratum 2",
                  name = "Substratum 2",
                  stratumId = actualStrata["Stratum 2"]?.id,
                  stableId = StableId("Stratum 2-Substratum 2"),
              ),
          ),
          actualSubstrata.map { it.copy(boundary = null) }.toSet(),
          "Substrata",
      )
    }

    @Test
    fun `creates initial history entry if simple site has a boundary`() {
      val gridOrigin = point(1)
      val boundary = Turtle(gridOrigin).makeMultiPolygon { square(150) }
      val exclusion = Turtle(gridOrigin).makeMultiPolygon { square(10) }

      val model =
          store.createPlantingSite(
              PlantingSiteModel.create(
                  boundary = boundary,
                  exclusion = exclusion,
                  name = "name",
                  organizationId = organizationId,
              )
          )

      assertNotNull(model.historyId, "History ID")

      assertEquals(
          listOf(
              PlantingSiteHistoriesRow(
                  areaHa = BigDecimal("2.2"),
                  boundary = boundary,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  exclusion = exclusion,
                  gridOrigin = gridOrigin,
                  id = model.historyId,
                  plantingSiteId = model.id,
              ),
          ),
          plantingSiteHistoriesDao.findAll(),
      )
    }

    @Test
    fun `creates initial history entries for detailed site`() {
      val gridOrigin = point(1)
      val siteBoundary = Turtle(gridOrigin).makeMultiPolygon { square(200) }
      val stratumBoundary = Turtle(gridOrigin).makeMultiPolygon { square(199.9) }
      val substratumBoundary = Turtle(gridOrigin).makeMultiPolygon { square(199.8) }
      val exclusion = Turtle(gridOrigin).makeMultiPolygon { square(5) }

      val model =
          store.createPlantingSite(
              PlantingSiteModel.create(
                  boundary = siteBoundary,
                  exclusion = exclusion,
                  name = "site",
                  organizationId = organizationId,
                  strata =
                      listOf(
                          StratumModel.create(
                              boundary = stratumBoundary,
                              exclusion = exclusion,
                              name = "stratum",
                              substrata =
                                  listOf(
                                      SubstratumModel.create(
                                          boundary = substratumBoundary,
                                          exclusion = exclusion,
                                          fullName = "stratum-substratum",
                                          name = "substratum",
                                      )
                                  ),
                          )
                      ),
              )
          )

      assertNotNull(model.historyId, "History ID")
      assertEquals(
          listOf(
              PlantingSiteHistoriesRow(
                  areaHa = BigDecimal("4.0"),
                  boundary = siteBoundary,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  exclusion = exclusion,
                  gridOrigin = gridOrigin,
                  id = model.historyId,
                  plantingSiteId = model.id,
              ),
          ),
          plantingSiteHistoriesDao.findAll(),
          "Planting site histories",
      )

      val stratumHistories = stratumHistoriesDao.findAll()
      assertEquals(
          listOf(
              StratumHistoriesRow(
                  areaHa = BigDecimal("4.0"),
                  boundary = stratumBoundary,
                  name = "stratum",
                  plantingSiteHistoryId = model.historyId,
                  stratumId = model.strata.first().id,
                  stableId = StableId("stratum"),
              ),
          ),
          stratumHistories.map { it.copy(id = null) },
          "Stratum histories",
      )

      assertEquals(
          listOf(
              SubstratumHistoriesRow(
                  areaHa = BigDecimal("4.0"),
                  boundary = substratumBoundary,
                  fullName = "stratum-substratum",
                  name = "substratum",
                  substratumId = model.strata.first().substrata.first().id,
                  stratumHistoryId = stratumHistories.first().id,
                  stableId = StableId("stratum-substratum"),
              ),
          ),
          substratumHistoriesDao.findAll().map { it.copy(id = null) },
          "Substratum histories",
      )

      assertTableEmpty(MONITORING_PLOT_HISTORIES)
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
              PlantingSiteModel.create(
                  name = "name",
                  organizationId = organizationId,
                  timeZone = timeZone,
              ),
              plantingSeasons =
                  listOf(
                      UpdatedPlantingSeasonModel(
                          startDate = season1StartDate,
                          endDate = season1EndDate,
                      ),
                      UpdatedPlantingSeasonModel(
                          startDate = season2StartDate,
                          endDate = season2EndDate,
                      ),
                  ),
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
            PlantingSiteModel.create(
                name = "name",
                organizationId = organizationId,
            )
        )
      }
    }

    @Test
    fun `throws exception if project is in a different organization`() {
      insertOrganization()
      val projectId = insertProject()

      assertThrows<ProjectInDifferentOrganizationException> {
        store.createPlantingSite(
            PlantingSiteModel.create(
                name = "name",
                organizationId = organizationId,
                projectId = projectId,
            )
        )
      }
    }

    // Validation rules are tested more comprehensively in PlantingSiteModelTest.
    @Test
    fun `rejects invalid planting site maps`() {
      val boundary = Turtle(point(0)).makeMultiPolygon { square(100) }
      val newModel =
          PlantingSiteModel.create(
              boundary = boundary,
              name = "name",
              organizationId = organizationId,
              strata =
                  listOf(
                      StratumModel.create(
                          boundary = boundary,
                          name = "name",
                          // Empty substratum list is invalid.
                          substrata = emptyList(),
                      )
                  ),
          )

      assertThrows<PlantingSiteMapInvalidException> { store.createPlantingSite(newModel) }
    }

    @Test
    fun `rejects overlapping planting seasons`() {
      clock.instant = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

      assertThrows<PlantingSeasonsOverlapException> {
        store.createPlantingSite(
            PlantingSiteModel.create(
                name = "name",
                organizationId = organizationId,
                timeZone = timeZone,
            ),
            plantingSeasons =
                listOf(
                    UpdatedPlantingSeasonModel(
                        startDate = LocalDate.of(2023, 1, 1),
                        endDate = LocalDate.of(2023, 2, 15),
                    ),
                    UpdatedPlantingSeasonModel(
                        startDate = LocalDate.of(2023, 2, 10),
                        endDate = LocalDate.of(2023, 5, 1),
                    ),
                ),
        )
      }
    }

    @Test
    fun `rejects invalid planting seasons`() {
      clock.instant = ZonedDateTime.of(2023, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC).toInstant()

      assertPlantingSeasonCreateThrows<CannotCreatePastPlantingSeasonException>(
          LocalDate.of(2022, 1, 1),
          LocalDate.of(2022, 6, 1),
      )
      assertPlantingSeasonCreateThrows<PlantingSeasonTooFarInFutureException>(
          LocalDate.of(2025, 1, 1),
          LocalDate.of(2025, 3, 1),
      )
      assertPlantingSeasonCreateThrows<PlantingSeasonTooLongException>(
          LocalDate.of(2023, 1, 1),
          LocalDate.of(2024, 1, 2),
      )
      assertPlantingSeasonCreateThrows<PlantingSeasonTooShortException>(
          LocalDate.of(2023, 1, 1),
          LocalDate.of(2023, 1, 15),
      )
    }

    private inline fun <reified T : Exception> assertPlantingSeasonCreateThrows(
        startDate: LocalDate,
        endDate: LocalDate,
    ) {
      assertThrows<T> {
        store.createPlantingSite(
            PlantingSiteModel.create(
                name = "name",
                organizationId = organizationId,
                timeZone = timeZone,
            ),
            plantingSeasons =
                listOf(UpdatedPlantingSeasonModel(startDate = startDate, endDate = endDate)),
        )
      }
    }
  }
}
