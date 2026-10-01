package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.EntityLocker
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.rectanglePolygon
import com.terraformation.backend.tracking.db.ObservationResultsInvalidator
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.util.GeometrySimplifier
import com.terraformation.backend.util.Turtle
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNull
import org.junit.jupiter.api.assertThrows

class PlantingSiteStoreCreateAdHocMonitoringPlotsTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  protected val eventPublisher = TestEventPublisher()
  protected val mockGeometrySimplifier = mockk<GeometrySimplifier>()
  protected val store: PlantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        TestSingletons.countryDetector,
        dslContext,
        EntityLocker(dslContext),
        eventPublisher,
        mockGeometrySimplifier,
        IdentifierGenerator(clock, dslContext),
        monitoringPlotsDao,
        ObservationResultsInvalidator(dslContext),
        ParentStore(dslContext),
        plantingSitesDao,
        eventPublisher,
        strataDao,
        substrataDao,
    )
  }

  @BeforeEach
  fun setUp() {
    every { mockGeometrySimplifier.simplify(any(), any()) } answers { firstArg() }

    insertOrganization()
    insertOrganizationUser(user.userId, inserted.organizationId, Role.Contributor)
  }

  @Nested
  inner class CreateAdHocMonitoringPlots {
    @Test
    fun `places plot in the substratum with the most overlap`() {
      val plantingSiteId = insertPlantingSite(width = 6)
      insertStratum(width = 6)
      insertSubstratum(width = 3)
      val substratumId = insertSubstratum(x = 3, width = 3)
      val substratumHistoryId = inserted.substratumHistoryId

      // 10 meters in the first substratum, 20 in the second.
      val coordinates = rectanglePolygon(1, x = 80).coordinates[0]
      val monitoringPlotId =
          store.createAdHocMonitoringPlot(plantingSiteId, point(coordinates.x, coordinates.y))

      assertEquals(substratumId, monitoringPlotsDao.fetchOneById(monitoringPlotId)!!.substratumId)
      val history = monitoringPlotHistoriesDao.fetchByMonitoringPlotId(monitoringPlotId).single()
      assertEquals(substratumId, history.substratumId, "History substratum ID")
      assertEquals(
          substratumHistoryId,
          history.substratumHistoryId,
          "History substratum history ID",
      )
    }

    @Test
    fun `does not place plot outside site boundary in a substratum`() {
      val plantingSiteId = insertPlantingSite()
      insertStratum()
      insertSubstratum()

      val monitoringPlotId = store.createAdHocMonitoringPlot(plantingSiteId, point(50))

      assertNull(monitoringPlotsDao.fetchOneById(monitoringPlotId)!!.substratumId, "Substratum ID")
      val history = monitoringPlotHistoriesDao.fetchByMonitoringPlotId(monitoringPlotId).single()
      assertNull(history.substratumHistoryId, "Substratum history ID")
    }

    @Test
    fun `inserts a monitoring plot row`() {
      val plantingSiteId = insertPlantingSite(boundary = multiPolygon(2.0))
      insertPlantingSiteHistory()

      val monitoringPlotId = store.createAdHocMonitoringPlot(plantingSiteId, point(0, 0))

      val plotBoundary = Turtle(point(0)).makePolygon { square(MONITORING_PLOT_SIZE_INT) }

      val expected =
          MonitoringPlotsRow(
              createdBy = user.userId,
              createdTime = clock.instant,
              id = monitoringPlotId,
              isAdHoc = true,
              isAvailable = false,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              organizationId = inserted.organizationId,
              plantingSiteId = plantingSiteId,
              substratumId = null,
              plotNumber = 1,
              sizeMeters = MONITORING_PLOT_SIZE_INT,
          )

      val actual = monitoringPlotsDao.fetchOneById(monitoringPlotId)!!

      assertEquals(expected, actual.copy(boundary = null))
      assertGeometryEquals(plotBoundary, actual.boundary)
    }

    @Test
    fun `throws not found exception if user not in organization`() {
      val plantingSiteId = insertPlantingSite(boundary = multiPolygon(2.0))
      insertPlantingSiteHistory()

      deleteOrganizationUser(user.userId, inserted.organizationId)

      assertThrows<PlantingSiteNotFoundException> {
        store.createAdHocMonitoringPlot(plantingSiteId, point(0, 0))
      }
    }
  }
}
