package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.ExistingProjectReportConfigModel
import com.terraformation.backend.accelerator.model.NewProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.accelerator.model.ReportStandardMetricEntryModel
import com.terraformation.backend.accelerator.model.ReportStandardMetricModel
import com.terraformation.backend.accelerator.model.StandardMetricModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.MetricComponent
import com.terraformation.backend.db.accelerator.MetricType
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.tables.records.ProjectReportConfigsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportStandardMetricsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportsRecord
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
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
    fun `includes current metrics for Not Submitted, and recorded metrics for Submitted`() {
      val standardMetricId1 =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Climate standard metric description",
              name = "Climate Standard Metric",
              reference = "3.0",
              type = MetricType.Activity,
          )

      val standardMetricId2 =
          insertStandardMetric(
              component = MetricComponent.Community,
              description = "Community metric description",
              name = "Community Metric",
              reference = "5.0",
              type = MetricType.Outcome,
          )

      val standardMetricId3 =
          insertStandardMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project objectives metric description",
              name = "Project Objectives Metric",
              reference = "1.0",
              type = MetricType.Impact,
          )

      val configId = insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted)

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          target = 55,
          value = 45,
          notes = "Almost at target",
          internalComment = "Not quite there yet",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          target = 25,
          modifiedTime = Instant.ofEpochSecond(1500),
          modifiedBy = user.userId,
      )

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
              standardMetrics =
                  listOf(
                      // ordered by reference
                      ReportStandardMetricModel(
                          metric =
                              StandardMetricModel(
                                  id = standardMetricId3,
                                  component = MetricComponent.ProjectObjectives,
                                  description = "Project objectives metric description",
                                  name = "Project Objectives Metric",
                                  reference = "1.0",
                                  type = MetricType.Impact,
                              ),
                          // all fields are null because no target/value have been set yet
                          entry = ReportStandardMetricEntryModel()),
                      ReportStandardMetricModel(
                          metric =
                              StandardMetricModel(
                                  id = standardMetricId1,
                                  component = MetricComponent.Climate,
                                  description = "Climate standard metric description",
                                  name = "Climate Standard Metric",
                                  reference = "3.0",
                                  type = MetricType.Activity,
                              ),
                          entry =
                              ReportStandardMetricEntryModel(
                                  target = 55,
                                  value = 45,
                                  notes = "Almost at target",
                                  internalComment = "Not quite there yet",
                                  modifiedTime = Instant.ofEpochSecond(3000),
                                  modifiedBy = user.userId,
                              )),
                      ReportStandardMetricModel(
                          metric =
                              StandardMetricModel(
                                  id = standardMetricId2,
                                  component = MetricComponent.Community,
                                  description = "Community metric description",
                                  name = "Community Metric",
                                  reference = "5.0",
                                  type = MetricType.Outcome,
                              ),
                          entry =
                              ReportStandardMetricEntryModel(
                                  target = 25,
                                  modifiedTime = Instant.ofEpochSecond(1500),
                                  modifiedBy = user.userId,
                              )),
                  ))

      assertEquals(listOf(reportModel), store.fetch(includeMetrics = true))
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
  inner class ReviewReport {
    @Test
    fun `throws Access Denied Exception for non-TFExpert users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.Submitted)

      assertThrows<AccessDeniedException> {
        store.reviewReport(
            reportId = reportId,
            status = ReportStatus.Approved,
            feedback = "feedback",
            internalComment = "internal comment",
        )
      }

      insertUserGlobalRole(role = GlobalRole.TFExpert)

      assertDoesNotThrow {
        store.reviewReport(
            reportId = reportId,
            status = ReportStatus.Approved,
            feedback = "feedback",
            internalComment = "internal comment",
        )
      }
    }

    @Test
    fun `throws Illegal State Exception if updating status of NotSubmitted or NotNeeded Reports`() {
      insertProjectReportConfig()
      val notSubmittedReportId = insertReport(status = ReportStatus.NotSubmitted)
      val notNeededReportId = insertReport(status = ReportStatus.NotNeeded)

      assertThrows<IllegalStateException> {
        store.reviewReport(
            reportId = notSubmittedReportId,
            status = ReportStatus.Approved,
            feedback = "feedback",
            internalComment = "internal comment",
        )
      }
      assertThrows<IllegalStateException> {
        store.reviewReport(
            reportId = notNeededReportId,
            status = ReportStatus.Approved,
            feedback = "feedback",
            internalComment = "internal comment",
        )
      }
      assertDoesNotThrow {
        store.reviewReport(
            reportId = notSubmittedReportId,
            status = ReportStatus.NotSubmitted,
            feedback = "feedback",
            internalComment = "internal comment",
        )
        store.reviewReport(
            reportId = notNeededReportId,
            status = ReportStatus.NotNeeded,
            feedback = "feedback",
            internalComment = "internal comment",
        )
      }
    }

    @Test
    fun `updates relevant columns`() {
      val otherUserId = insertUser()

      insertProjectReportConfig()
      val reportId =
          insertReport(
              status = ReportStatus.Submitted,
              modifiedBy = otherUserId,
              modifiedTime = Instant.ofEpochSecond(3000),
          )

      val existingReport = reportsDao.fetchOneById(reportId)!!

      clock.instant = Instant.ofEpochSecond(20000)

      store.reviewReport(
          reportId = reportId,
          status = ReportStatus.NeedsUpdate,
          feedback = "feedback",
          internalComment = "internal comment",
      )

      val updatedReport =
          existingReport.copy(
              statusId = ReportStatus.NeedsUpdate,
              feedback = "feedback",
              internalComment = "internal comment",
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )

      assertTableEquals(ReportsRecord(updatedReport))
    }
  }

  @Nested
  inner class ReviewReportStandardMetrics {
    @Test
    fun `throws exception for non-TFExpert users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.Submitted)

      assertThrows<AccessDeniedException> {
        store.reviewReportStandardMetrics(reportId = reportId, emptyMap())
      }

      insertUserGlobalRole(role = GlobalRole.TFExpert)

      assertDoesNotThrow { store.reviewReportStandardMetrics(reportId = reportId, emptyMap()) }
    }

    @Test
    fun `upserts values and internalComment for existing and non-existing report metric rows`() {
      val otherUserId = insertUser()

      val standardMetricId1 =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Climate standard metric description",
              name = "Climate Standard Metric",
              reference = "3.0",
              type = MetricType.Activity,
          )

      val standardMetricId2 =
          insertStandardMetric(
              component = MetricComponent.Community,
              description = "Community metric description",
              name = "Community Metric",
              reference = "5.0",
              type = MetricType.Outcome,
          )

      val standardMetricId3 =
          insertStandardMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project objectives metric description",
              name = "Project Objectives Metric",
              reference = "1.0",
              type = MetricType.Impact,
          )

      // This has no entry and will not have any updates
      insertStandardMetric(
          component = MetricComponent.Biodiversity,
          description = "Biodiversity metric description",
          name = "Biodiversity Metric",
          reference = "7.0",
          type = MetricType.Impact,
      )

      val configId = insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.Submitted, createdBy = otherUserId)

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          target = 55,
          value = 45,
          notes = "Existing metric 1 notes",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          target = 30,
          value = null,
          notes = "Existing metric 2 notes",
          internalComment = "Existing metric 2 internal comments",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      // At this point, the report has entries for metric 1 and 2, no entry for metric 3 and 4
      clock.instant = Instant.ofEpochSecond(9000)

      // We add new entries for metric 2 and 3. Metric 1 and 4 are not modified
      store.reviewReportStandardMetrics(
          reportId,
          mapOf(
              standardMetricId2 to
                  ReportStandardMetricEntryModel(
                      target = 99,
                      value = 88,
                      notes = "New metric 2 notes",
                      internalComment = "New metric 2 internal comment",

                      // These fields are ignored
                      modifiedTime = Instant.EPOCH,
                      modifiedBy = UserId(99),
                  ),
              standardMetricId3 to
                  ReportStandardMetricEntryModel(
                      target = 50,
                      value = 45,
                      notes = "New metric 3 notes",
                      internalComment = "New metric 3 internal comment",
                  ),
          ))

      assertTableEquals(
          listOf(
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId1,
                  target = 55,
                  value = 45,
                  notes = "Existing metric 1 notes",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = otherUserId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId2,
                  target = 99,
                  value = 88,
                  notes = "New metric 2 notes",
                  internalComment = "New metric 2 internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId3,
                  target = 50,
                  value = 45,
                  notes = "New metric 3 notes",
                  internalComment = "New metric 3 internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              // Standard metric 4 is not inserted since there was no updates
          ),
          "Reports standard metrics table")

      assertTableEquals(
          ReportsRecord(
              id = reportId,
              configId = configId,
              projectId = projectId,
              statusId = ReportStatus.Submitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = otherUserId,
              createdTime = Instant.EPOCH,
              // Modified time and modified by are updated
              modifiedBy = user.userId,
              modifiedTime = Instant.ofEpochSecond(9000),
          ),
          "Reports table")
    }
  }

  @Nested
  inner class UpdateReportStandardMetrics {
    @Test
    fun `throws exception for non-organization users`() {
      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted)
      deleteOrganizationUser()
      assertThrows<AccessDeniedException> {
        store.updateReportStandardMetrics(reportId, emptyMap())
      }
    }

    @Test
    fun `throws exception for reports not in NotSubmitted`() {
      insertProjectReportConfig()
      val notNeededReportId = insertReport(status = ReportStatus.NotNeeded)
      val submittedReportId = insertReport(status = ReportStatus.Submitted)
      val needsUpdateReportId = insertReport(status = ReportStatus.NeedsUpdate)
      val approvedReportId = insertReport(status = ReportStatus.Approved)

      assertThrows<IllegalStateException> {
        store.updateReportStandardMetrics(notNeededReportId, emptyMap())
      }
      assertThrows<IllegalStateException> {
        store.updateReportStandardMetrics(submittedReportId, emptyMap())
      }
      assertThrows<IllegalStateException> {
        store.updateReportStandardMetrics(needsUpdateReportId, emptyMap())
      }
      assertThrows<IllegalStateException> {
        store.updateReportStandardMetrics(approvedReportId, emptyMap())
      }
    }

    @Test
    fun `upserts values and targets for existing and non-existing report metric rows`() {
      val otherUserId = insertUser()

      val standardMetricId1 =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Climate standard metric description",
              name = "Climate Standard Metric",
              reference = "3.0",
              type = MetricType.Activity,
          )

      val standardMetricId2 =
          insertStandardMetric(
              component = MetricComponent.Community,
              description = "Community metric description",
              name = "Community Metric",
              reference = "5.0",
              type = MetricType.Outcome,
          )

      val standardMetricId3 =
          insertStandardMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project objectives metric description",
              name = "Project Objectives Metric",
              reference = "1.0",
              type = MetricType.Impact,
          )

      // This has no entry and will not have any updates
      insertStandardMetric(
          component = MetricComponent.Biodiversity,
          description = "Biodiversity metric description",
          name = "Biodiversity Metric",
          reference = "7.0",
          type = MetricType.Impact,
      )

      val configId = insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted, createdBy = otherUserId)

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          target = 55,
          value = 45,
          notes = "Existing metric 1 notes",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          target = 30,
          value = null,
          notes = "Existing metric 2 notes",
          internalComment = "Existing metric 2 internal comments",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      // At this point, the report has entries for metric 1 and 2, no entry for metric 3 and 4

      clock.instant = Instant.ofEpochSecond(9000)

      // We add new entries for metric 2 and 3. Metric 1 and 4 are not modified

      store.updateReportStandardMetrics(
          reportId,
          mapOf(
              standardMetricId2 to
                  ReportStandardMetricEntryModel(
                      target = 99,
                      value = 88,
                      notes = "New metric 2 notes",

                      // These fields are ignored
                      internalComment = "Not permitted to write internal comment",
                      modifiedTime = Instant.EPOCH,
                      modifiedBy = UserId(99),
                  ),
              standardMetricId3 to
                  ReportStandardMetricEntryModel(
                      target = 50,
                      value = null,
                      notes = "New metric 3 notes",
                  ),
          ))

      assertTableEquals(
          listOf(
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId1,
                  target = 55,
                  value = 45,
                  notes = "Existing metric 1 notes",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = otherUserId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId2,
                  target = 99,
                  value = 88,
                  notes = "New metric 2 notes",
                  internalComment = "Existing metric 2 internal comments",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId3,
                  target = 50,
                  notes = "New metric 3 notes",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              // Standard metric 4 is not inserted since there was no updates
          ),
          "Reports standard metrics table")

      assertTableEquals(
          ReportsRecord(
              id = reportId,
              configId = configId,
              projectId = projectId,
              statusId = ReportStatus.NotSubmitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = otherUserId,
              createdTime = Instant.EPOCH,
              // Modified time and modified by are updated
              modifiedBy = user.userId,
              modifiedTime = Instant.ofEpochSecond(9000),
          ),
          "Reports table")
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
