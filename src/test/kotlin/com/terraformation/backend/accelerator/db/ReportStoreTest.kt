package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.ExistingProjectReportConfigModel
import com.terraformation.backend.accelerator.model.NewProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.tables.records.ProjectReportConfigsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportsRecord
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.time.toInstant
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ReportStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()

  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }
  private val store: ReportStore by lazy { ReportStore(clock, dslContext, reportsDao, systemUser) }

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setup() {
    organizationId = insertOrganization()
    projectId = insertProject()
    insertOrganizationUser(role = Role.Admin)
    insertUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
  }

  @Nested
  inner class Fetch {
    @Test
    fun `returns report details`() {
      val configId = insertProjectReportConfig()
      val reportId =
          insertReport(
              status = ReportStatus.NeedsUpdate,
              startDate = LocalDate.of(2030, Month.JANUARY, 1),
              endDate = LocalDate.of(2030, Month.DECEMBER, 31),
              internalComment = "internal comment",
              feedback = "feedback",
              createdBy = systemUser.userId,
              createdTime = Instant.ofEpochSecond(4000),
              modifiedBy = user.userId,
              modifiedTime = Instant.ofEpochSecond(8000),
              submittedBy = user.userId,
              submittedTime = Instant.ofEpochSecond(6000),
          )

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              status = ReportStatus.NeedsUpdate,
              startDate = LocalDate.of(2030, Month.JANUARY, 1),
              endDate = LocalDate.of(2030, Month.DECEMBER, 31),
              internalComment = "internal comment",
              feedback = "feedback",
              createdBy = systemUser.userId,
              createdTime = Instant.ofEpochSecond(4000),
              modifiedBy = user.userId,
              modifiedTime = Instant.ofEpochSecond(8000),
              submittedBy = user.userId,
              submittedTime = Instant.ofEpochSecond(6000),
          )

      clock.instant = LocalDate.of(2031, Month.JANUARY, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
      assertEquals(listOf(reportModel), store.fetch())
    }

    @Test
    fun `hides Not Needed reports by default`() {
      val configId = insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotNeeded)

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              status = ReportStatus.NotNeeded,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH)

      assertEquals(emptyList<ReportModel>(), store.fetch())

      assertEquals(listOf(reportModel), store.fetch(includeArchived = true))
    }

    @Test
    fun `hides reports ending more than 30 days into the future by default`() {
      val today = LocalDate.of(2025, Month.FEBRUARY, 15)
      clock.instant = today.atStartOfDay().toInstant(ZoneOffset.UTC)

      val configId = insertProjectReportConfig()
      val reportId = insertReport(startDate = today, endDate = today.plusDays(31))

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              status = ReportStatus.NotSubmitted,
              startDate = today,
              endDate = today.plusDays(31),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH)

      assertEquals(emptyList<ReportModel>(), store.fetch())

      assertEquals(listOf(reportModel), store.fetch(includeFuture = true))
    }

    @Test
    fun `hides internal comment for non global role users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val configId = insertProjectReportConfig()
      val reportId = insertReport(internalComment = "internal comment")

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              internalComment = "internal comment")

      assertEquals(
          listOf(reportModel.copy(internalComment = null)),
          store.fetch(),
          "Org user cannot see internal comment")

      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      assertEquals(
          listOf(reportModel), store.fetch(), "Read-only Global role can see internal comment")
    }

    @Test
    fun `filters by projectId or end year`() {
      val configId = insertProjectReportConfig()
      val reportId1 = insertReport(endDate = LocalDate.of(2030, Month.DECEMBER, 31))
      val reportId2 = insertReport(endDate = LocalDate.of(2035, Month.DECEMBER, 31))

      val otherProjectId = insertProject()
      val otherConfigId = insertProjectReportConfig()
      val otherReportId1 = insertReport(endDate = LocalDate.of(2035, Month.DECEMBER, 31))
      val otherReportId2 = insertReport(endDate = LocalDate.of(2040, Month.DECEMBER, 31))

      val reportModel1 =
          ReportModel(
              id = reportId1,
              configId = configId,
              projectId = projectId,
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.of(2030, Month.DECEMBER, 31),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
          )

      val reportModel2 =
          reportModel1.copy(
              id = reportId2,
              endDate = LocalDate.of(2035, Month.DECEMBER, 31),
          )

      val otherReportModel1 =
          reportModel2.copy(
              id = otherReportId1,
              configId = otherConfigId,
              projectId = otherProjectId,
          )

      val otherReportModel2 =
          otherReportModel1.copy(
              id = otherReportId2,
              endDate = LocalDate.of(2040, Month.DECEMBER, 31),
          )

      clock.instant = LocalDate.of(2041, Month.JANUARY, 1).atStartOfDay().toInstant(ZoneOffset.UTC)

      assertEquals(
          setOf(reportModel1, reportModel2, otherReportModel1, otherReportModel2),
          store.fetch().toSet(),
          "Fetches all")

      assertEquals(
          setOf(reportModel1, reportModel2),
          store.fetch(projectId = projectId).toSet(),
          "Fetches by projectId")

      assertEquals(
          setOf(reportModel2, otherReportModel1),
          store.fetch(year = 2035).toSet(),
          "Fetches by year")

      assertEquals(
          listOf(reportModel2),
          store.fetch(projectId = projectId, year = 2035),
          "Fetches by projectId and year")
    }

    @Test
    fun `returns only visible reports`() {
      deleteOrganizationUser(organizationId = organizationId)
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val configId = insertProjectReportConfig()
      val reportId = insertReport()

      val secondProjectId = insertProject()
      val secondConfigId = insertProjectReportConfig()
      val secondReportId = insertReport()

      insertOrganization()
      val otherProjectId = insertProject()
      val otherConfigId = insertProjectReportConfig()
      val otherReportId = insertReport()

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
          )

      val secondReportModel =
          reportModel.copy(
              id = secondReportId,
              configId = secondConfigId,
              projectId = secondProjectId,
          )

      val otherReportModel =
          reportModel.copy(
              id = otherReportId,
              configId = otherConfigId,
              projectId = otherProjectId,
          )

      assertEquals(
          emptyList<ReportModel>(),
          store.fetch(),
          "User not in organizations cannot see the reports")

      insertOrganizationUser(organizationId = organizationId, role = Role.Contributor)
      assertEquals(emptyList<ReportModel>(), store.fetch(), "Contributor cannot see the reports")

      insertOrganizationUser(organizationId = organizationId, role = Role.Manager)
      assertEquals(
          setOf(reportModel, secondReportModel),
          store.fetch().toSet(),
          "Manager can see project reports within the organization")

      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertEquals(
          setOf(reportModel, secondReportModel, otherReportModel),
          store.fetch().toSet(),
          "Read-only admin user can see all project reports")
    }
  }

  @Nested
  inner class FetchProjectReportConfigs {
    @Test
    fun `throws exception for non accelerator admin users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      assertThrows<AccessDeniedException> { store.fetchProjectReportConfigs() }
    }

    @Test
    fun `queries by project`() {
      val otherProjectId = insertProject()

      val projectConfigId1 =
          insertProjectReportConfig(
              projectId = projectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2028, Month.MARCH, 2),
          )

      val projectConfigId2 =
          insertProjectReportConfig(
              projectId = projectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.JANUARY, 7),
              reportingEndDate = LocalDate.of(2031, Month.MAY, 9),
          )

      val otherProjectConfigId1 =
          insertProjectReportConfig(
              projectId = otherProjectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2027, Month.FEBRUARY, 6),
              reportingEndDate = LocalDate.of(2031, Month.JULY, 9),
          )

      val otherProjectConfigId2 =
          insertProjectReportConfig(
              projectId = otherProjectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2031, Month.JANUARY, 18),
              reportingEndDate = LocalDate.of(2039, Month.MAY, 31),
          )

      val projectConfigModel1 =
          ExistingProjectReportConfigModel(
              id = projectConfigId1,
              projectId = projectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2028, Month.MARCH, 2),
          )

      val projectConfigModel2 =
          ExistingProjectReportConfigModel(
              id = projectConfigId2,
              projectId = projectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.JANUARY, 7),
              reportingEndDate = LocalDate.of(2031, Month.MAY, 9),
          )

      val otherProjectConfigModel1 =
          ExistingProjectReportConfigModel(
              id = otherProjectConfigId1,
              projectId = otherProjectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2027, Month.FEBRUARY, 6),
              reportingEndDate = LocalDate.of(2031, Month.JULY, 9),
          )

      val otherProjectConfigModel2 =
          ExistingProjectReportConfigModel(
              id = otherProjectConfigId2,
              projectId = otherProjectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2031, Month.JANUARY, 18),
              reportingEndDate = LocalDate.of(2039, Month.MAY, 31),
          )

      assertEquals(
          setOf(projectConfigModel1, projectConfigModel2),
          store.fetchProjectReportConfigs(projectId = projectId).toSet(),
          "fetches by projectId")

      assertEquals(
          setOf(
              projectConfigModel1,
              projectConfigModel2,
              otherProjectConfigModel1,
              otherProjectConfigModel2),
          store.fetchProjectReportConfigs().toSet(),
          "fetches all project configs")
    }
  }

  @Nested
  inner class InsertProjectReportConfig {
    @Test
    fun `throws exception for non accelerator admin users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val config =
          NewProjectReportConfigModel(
              id = null,
              projectId = projectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2028, Month.MARCH, 2),
          )

      assertThrows<AccessDeniedException> { store.insertProjectReportConfig(config) }
    }

    @Test
    fun `inserts config record and creates reports for annual frequency`() {
      clock.instant = Instant.ofEpochSecond(9000)

      val config =
          NewProjectReportConfigModel(
              id = null,
              projectId = projectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2028, Month.MARCH, 2),
          )

      store.insertProjectReportConfig(config)

      val configId = projectReportConfigsDao.fetchByProjectId(projectId).single().id

      assertTableEquals(
          ProjectReportConfigsRecord(
              id = configId,
              projectId = projectId,
              reportFrequencyId = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2028, Month.MARCH, 2),
          ),
          "Project report config tables")

      assertTableEquals(
          listOf(
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2025, Month.JANUARY, 1),
                  endDate = LocalDate.of(2025, Month.DECEMBER, 31),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2026, Month.JANUARY, 1),
                  endDate = LocalDate.of(2026, Month.DECEMBER, 31),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2027, Month.JANUARY, 1),
                  endDate = LocalDate.of(2027, Month.DECEMBER, 31),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2028, Month.JANUARY, 1),
                  endDate = LocalDate.of(2028, Month.DECEMBER, 31),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              )),
          "Reports table",
      )
    }

    @Test
    fun `inserts config record and creates reports for quarterly frequency`() {
      clock.instant = Instant.ofEpochSecond(9000)

      val config =
          NewProjectReportConfigModel(
              id = null,
              projectId = projectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2026, Month.MARCH, 31),
          )

      store.insertProjectReportConfig(config)

      val configId = projectReportConfigsDao.fetchByProjectId(projectId).single().id

      assertTableEquals(
          ProjectReportConfigsRecord(
              id = configId,
              projectId = projectId,
              reportFrequencyId = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2026, Month.MARCH, 31),
          ),
          "Project report config tables")

      assertTableEquals(
          listOf(
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2025, Month.APRIL, 1),
                  endDate = LocalDate.of(2025, Month.JUNE, 30),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2025, Month.JULY, 1),
                  endDate = LocalDate.of(2025, Month.SEPTEMBER, 30),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2025, Month.OCTOBER, 1),
                  endDate = LocalDate.of(2025, Month.DECEMBER, 31),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2026, Month.JANUARY, 1),
                  endDate = LocalDate.of(2026, Month.MARCH, 31),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              )),
          "Reports table",
      )
    }
  }
}
