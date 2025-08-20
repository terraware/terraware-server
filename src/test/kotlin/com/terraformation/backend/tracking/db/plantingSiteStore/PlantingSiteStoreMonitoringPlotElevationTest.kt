package com.terraformation.backend.tracking.db.plantingSiteStore

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.TestSingletons
import com.terraformation.backend.customer.db.ParentStore
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.IdentifierGenerator
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.tracking.tables.records.MonitoringPlotsRecord
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.polygon
import com.terraformation.backend.tracking.db.PlantingSiteStore
import com.terraformation.backend.tracking.model.MonitoringPlotModel
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class PlantingSiteStoreMonitoringPlotElevationTest : DatabaseTest(), RunsAsDatabaseUser {
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

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    organizationId = insertOrganization()
    insertOrganizationUser(user.userId, organizationId, Role.Admin)
  }

  @Nested
  inner class FetchMonitoringPlotsWithoutElevation {
    @Test
    fun `returns only monitoring plots without elevation and within user organizations`() {
      val secondOrganizationId = insertOrganization()
      insertOrganizationUser(user.userId, secondOrganizationId, Role.Contributor)

      val otherUserId = insertUser()
      val otherOrganizationId = insertOrganization(createdBy = otherUserId)
      insertOrganizationUser(otherUserId, otherOrganizationId, Role.Admin)

      insertPlantingSite(boundary = multiPolygon(2.0), organizationId = organizationId)
      val org1Site1PlotId1 =
          insertMonitoringPlot(
              boundary = polygon(1.0),
              elevationMeters = null,
              organizationId = organizationId,
              plotNumber = 1L,
          )
      val org1Site1PlotId2 =
          insertMonitoringPlot(
              boundary = polygon(1.5),
              elevationMeters = null,
              organizationId = organizationId,
              plotNumber = 2L,
          )
      // Not visible because it has elevation
      insertMonitoringPlot(
          boundary = polygon(10.0),
          elevationMeters = BigDecimal.TEN,
          organizationId = organizationId,
          plotNumber = 3L,
      )

      insertPlantingSite(boundary = multiPolygon(4.0), organizationId = organizationId)
      val org1Site2PlotId =
          insertMonitoringPlot(
              boundary = polygon(2.0),
              elevationMeters = null,
              organizationId = organizationId,
              plotNumber = 4L,
          )

      insertPlantingSite(boundary = multiPolygon(3.0), organizationId = secondOrganizationId)
      val org2PlotId =
          insertMonitoringPlot(
              boundary = polygon(3.0),
              elevationMeters = null,
              organizationId = secondOrganizationId,
              plotNumber = 1L,
          )

      insertPlantingSite(boundary = multiPolygon(10.0), organizationId = otherOrganizationId)
      insertPlantingSiteHistory()
      val otherOrgPlotId =
          insertMonitoringPlot(
              boundary = polygon(4.0),
              elevationMeters = null,
              organizationId = otherOrganizationId,
              plotNumber = 1L,
          )
      // Not visible because it has elevation
      insertMonitoringPlot(
          boundary = polygon(10.0),
          elevationMeters = BigDecimal.TWO,
          organizationId = otherOrganizationId,
          plotNumber = 2L,
      )

      val org1Site1PlotModel1 =
          MonitoringPlotModel(
              boundary = polygon(1.0),
              elevationMeters = null,
              id = org1Site1PlotId1,
              isAdHoc = false,
              isAvailable = true,
              permanentIndex = null,
              plotNumber = 1L,
              sizeMeters = 30,
          )
      val org1Site1PlotModel2 =
          org1Site1PlotModel1.copy(boundary = polygon(1.5), id = org1Site1PlotId2, plotNumber = 2L)

      val org1Site2PlotModel =
          org1Site1PlotModel1.copy(boundary = polygon(2.0), id = org1Site2PlotId, plotNumber = 4L)

      val org2PlotIdModel =
          org1Site1PlotModel1.copy(boundary = polygon(3.0), id = org2PlotId, plotNumber = 1L)

      val otherOrgPlotModel =
          org1Site1PlotModel1.copy(boundary = polygon(4.0), id = otherOrgPlotId, plotNumber = 1L)

      assertEquals(
          listOf(org2PlotIdModel, org1Site2PlotModel, org1Site1PlotModel2, org1Site1PlotModel1),
          store.fetchMonitoringPlotsWithoutElevation(),
          "Organization user",
      )

      assertEquals(
          listOf(org2PlotIdModel, org1Site2PlotModel),
          store.fetchMonitoringPlotsWithoutElevation(2),
          "Organization user with fetch limit",
      )

      val systemUser = SystemUser(usersDao)
      assertEquals(
          listOf(
              otherOrgPlotModel,
              org2PlotIdModel,
              org1Site2PlotModel,
              org1Site1PlotModel2,
              org1Site1PlotModel1,
          ),
          systemUser.run { store.fetchMonitoringPlotsWithoutElevation() },
          "System user",
      )
    }
  }

  @Nested
  inner class UpdateMonitoringPlotElevation {
    @Test
    fun `updates plots within the organization for organization admin`() {
      val secondOrganizationId = insertOrganization()
      insertOrganizationUser(user.userId, secondOrganizationId, Role.Manager)

      val otherUserId = insertUser()
      val otherOrganizationId = insertOrganization(createdBy = otherUserId)
      insertOrganizationUser(otherUserId, otherOrganizationId, Role.Admin)

      insertPlantingSite(boundary = multiPolygon(2.0), organizationId = organizationId)
      val org1Site1PlotId1 =
          insertMonitoringPlot(
              boundary = polygon(1.0),
              elevationMeters = null,
              organizationId = organizationId,
              plotNumber = 1L,
          )
      val org1Site1PlotId2 =
          insertMonitoringPlot(
              boundary = polygon(1.5),
              elevationMeters = BigDecimal.ZERO,
              organizationId = organizationId,
              plotNumber = 2L,
          )

      insertPlantingSite(boundary = multiPolygon(4.0), organizationId = organizationId)
      val org1Site2PlotId =
          insertMonitoringPlot(
              boundary = polygon(2.0),
              elevationMeters = null,
              organizationId = organizationId,
              plotNumber = 4L,
          )

      insertPlantingSite(boundary = multiPolygon(3.0), organizationId = secondOrganizationId)
      val org2PlotId =
          insertMonitoringPlot(
              boundary = polygon(3.0),
              elevationMeters = null,
              organizationId = secondOrganizationId,
              plotNumber = 1L,
          )

      insertPlantingSite(boundary = multiPolygon(10.0), organizationId = otherOrganizationId)
      insertPlantingSiteHistory()
      val otherOrgPlotId =
          insertMonitoringPlot(
              boundary = polygon(4.0),
              elevationMeters = null,
              organizationId = otherOrganizationId,
              plotNumber = 1L,
          )

      val existingRows = monitoringPlotsDao.findAll()

      val rowsUpdated =
          store.updateMonitoringPlotElevation(
              mapOf(
                  org1Site1PlotId1 to BigDecimal.TEN,
                  org1Site1PlotId2 to BigDecimal.TWO,
                  org1Site2PlotId to BigDecimal.ONE,
                  org2PlotId to BigDecimal.TEN, // User not an admin
                  otherOrgPlotId to BigDecimal.TEN, // User not in organization
              )
          )

      val updatedRecords =
          existingRows.map { row ->
            when (row.id) {
              org1Site1PlotId1 -> MonitoringPlotsRecord(row.copy(elevationMeters = BigDecimal.TEN))
              org1Site1PlotId2 -> MonitoringPlotsRecord(row.copy(elevationMeters = BigDecimal.TWO))
              org1Site2PlotId -> MonitoringPlotsRecord(row.copy(elevationMeters = BigDecimal.ONE))
              else -> MonitoringPlotsRecord(row)
            }
          }

      assertTableEquals(updatedRecords, "Monitoring plots table")
      assertEquals(3, rowsUpdated, "Rows updated")
    }

    @Test
    fun `system user updates all plots`() {
      val secondOrganizationId = insertOrganization()
      insertOrganizationUser(user.userId, secondOrganizationId, Role.Admin)

      val otherUserId = insertUser()
      val otherOrganizationId = insertOrganization(createdBy = otherUserId)
      insertOrganizationUser(otherUserId, otherOrganizationId, Role.Admin)

      insertPlantingSite(boundary = multiPolygon(2.0), organizationId = organizationId)
      val org1Site1PlotId1 =
          insertMonitoringPlot(
              boundary = polygon(1.0),
              elevationMeters = null,
              organizationId = organizationId,
              plotNumber = 1L,
          )
      val org1Site1PlotId2 =
          insertMonitoringPlot(
              boundary = polygon(1.5),
              elevationMeters = BigDecimal.ZERO,
              organizationId = organizationId,
              plotNumber = 2L,
          )

      insertPlantingSite(boundary = multiPolygon(4.0), organizationId = organizationId)
      val org1Site2PlotId =
          insertMonitoringPlot(
              boundary = polygon(2.0),
              elevationMeters = null,
              organizationId = organizationId,
              plotNumber = 4L,
          )

      insertPlantingSite(boundary = multiPolygon(3.0), organizationId = secondOrganizationId)
      val org2PlotId =
          insertMonitoringPlot(
              boundary = polygon(3.0),
              elevationMeters = null,
              organizationId = secondOrganizationId,
              plotNumber = 1L,
          )

      insertPlantingSite(boundary = multiPolygon(10.0), organizationId = otherOrganizationId)
      insertPlantingSiteHistory()
      val otherOrgPlotId =
          insertMonitoringPlot(
              boundary = polygon(4.0),
              elevationMeters = null,
              organizationId = otherOrganizationId,
              plotNumber = 1L,
          )

      val existingRows = monitoringPlotsDao.findAll()

      val systemUser = SystemUser(usersDao)
      val rowsUpdated =
          systemUser.run {
            store.updateMonitoringPlotElevation(
                mapOf(
                    org1Site1PlotId1 to BigDecimal.TEN,
                    org1Site1PlotId2 to BigDecimal.TWO,
                    org1Site2PlotId to BigDecimal.ONE,
                    org2PlotId to BigDecimal.TEN,
                    otherOrgPlotId to BigDecimal.TEN,
                )
            )
          }

      val updatedRecords =
          existingRows.map { row ->
            when (row.id) {
              org1Site1PlotId1 -> MonitoringPlotsRecord(row.copy(elevationMeters = BigDecimal.TEN))
              org1Site1PlotId2 -> MonitoringPlotsRecord(row.copy(elevationMeters = BigDecimal.TWO))
              org1Site2PlotId -> MonitoringPlotsRecord(row.copy(elevationMeters = BigDecimal.ONE))
              org2PlotId -> MonitoringPlotsRecord(row.copy(elevationMeters = BigDecimal.TEN))
              otherOrgPlotId -> MonitoringPlotsRecord(row.copy(elevationMeters = BigDecimal.TEN))
              else -> MonitoringPlotsRecord(row)
            }
          }

      assertTableEquals(updatedRecords, "Monitoring plots table")
      assertEquals(5, rowsUpdated, "Rows updated")
    }

    @Test
    fun `updates no rows if empty map`() {
      assertEquals(0, store.updateMonitoringPlotElevation(emptyMap()), "Empty map")
    }

    @Test
    fun `updates no rows if no permission`() {
      val otherUserId = insertUser()
      val otherOrganizationId = insertOrganization(createdBy = otherUserId)
      insertOrganizationUser(otherUserId, otherOrganizationId, Role.Admin)
      insertPlantingSite(boundary = multiPolygon(10.0), organizationId = otherOrganizationId)
      insertPlantingSiteHistory()
      val otherOrgPlotId =
          insertMonitoringPlot(
              boundary = polygon(4.0),
              elevationMeters = null,
              organizationId = otherOrganizationId,
              plotNumber = 1L,
          )

      assertEquals(
          0,
          store.updateMonitoringPlotElevation(mapOf(otherOrgPlotId to BigDecimal.ZERO)),
          "Not visible plot",
      )
    }
  }
}
