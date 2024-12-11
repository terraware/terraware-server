package com.terraformation.backend.tracking.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.util.Turtle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class PlantingSiteStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  protected val clock = TestClock()
  protected val eventPublisher = TestEventPublisher()
  protected val store: PlantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        TestSingletons.countryDetector,
        dslContext,
        eventPublisher,
        monitoringPlotsDao,
        ParentStore(dslContext),
        plantingSeasonsDao,
        plantingSitesDao,
        plantingSubzonesDao,
        plantingZonesDao)
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(user.userId, inserted.organizationId, Role.Admin)
  }

  @Nested
  inner class CreateAdHocMonitoringPlots {
    @Test
    fun `inserts a monitoring plot row`() {
      val plantingSiteId = insertPlantingSite(boundary = multiPolygon(2.0))
      insertPlantingSiteHistory()

      val monitoringPlotId =
          store.createAdHocMonitoringPlot("ad-hoc-plot", plantingSiteId, point(0, 0))

      val plotBoundary = Turtle(point(0)).makePolygon { square(MONITORING_PLOT_SIZE_INT) }

      val expected =
          MonitoringPlotsRow(
              createdBy = user.userId,
              createdTime = clock.instant,
              fullName = "ad-hoc-plot",
              id = monitoringPlotId,
              isAdHoc = true,
              isAvailable = false,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
              name = "ad-hoc-plot",
              plantingSiteId = plantingSiteId,
              plantingSubzoneId = null,
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
        store.createAdHocMonitoringPlot("ad-hoc-plot", plantingSiteId, point(0, 0))
      }
    }

    @Test
    fun `throws access denied exception if user is a contributor`() {
      val plantingSiteId = insertPlantingSite(boundary = multiPolygon(2.0))
      insertPlantingSiteHistory()

      deleteOrganizationUser(user.userId, inserted.organizationId)
      insertOrganizationUser(user.userId, inserted.organizationId, Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.createAdHocMonitoringPlot("ad-hoc-plot", plantingSiteId, point(0, 0))
      }
    }
  }
}
