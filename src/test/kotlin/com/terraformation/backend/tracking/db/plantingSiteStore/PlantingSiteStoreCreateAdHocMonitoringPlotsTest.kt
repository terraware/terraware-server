package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.assertGeometryEquals
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.tables.pojos.MonitoringPlotsRow
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.point
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.MONITORING_PLOT_SIZE_INT
import com.terraformation.backend.util.Turtle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PlantingSiteStoreCreateAdHocMonitoringPlotsTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  protected val clock = TestClock()
  protected val eventPublisher = TestEventPublisher()
  protected val store: PlantingSiteStore by lazy {
    PlantingSiteStore(
        clock,
        TestSingletons.countryDetector,
        dslContext,
        eventPublisher,
        IdentifierGenerator(clock, dslContext),
        monitoringPlotsDao,
        ParentStore(dslContext),
        plantingSeasonsDao,
        plantingSitesDao,
        plantingSubzonesDao,
        plantingZonesDao,
    )
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationUser(user.userId, inserted.organizationId, Role.Contributor)
  }

  @Nested
  inner class CreateAdHocMonitoringPlots {
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
              plantingSubzoneId = null,
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
