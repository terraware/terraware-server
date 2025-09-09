package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.AcceleratorReportPublishedEvent
import com.terraformation.backend.accelerator.event.AcceleratorReportSubmittedEvent
import com.terraformation.backend.accelerator.event.AcceleratorReportUpcomingEvent
import com.terraformation.backend.accelerator.model.ExistingProjectReportConfigModel
import com.terraformation.backend.accelerator.model.NewProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ProjectMetricModel
import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.accelerator.model.ReportMetricEntryModel
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.accelerator.model.ReportPhotoModel
import com.terraformation.backend.accelerator.model.ReportProjectMetricModel
import com.terraformation.backend.accelerator.model.ReportStandardMetricModel
import com.terraformation.backend.accelerator.model.ReportSystemMetricEntryModel
import com.terraformation.backend.accelerator.model.ReportSystemMetricModel
import com.terraformation.backend.accelerator.model.StandardMetricModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.accelerator.MetricComponent
import com.terraformation.backend.db.accelerator.MetricType
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportMetricStatus
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.SystemMetric
import com.terraformation.backend.db.accelerator.tables.records.ProjectAcceleratorDetailsRecord
import com.terraformation.backend.db.accelerator.tables.records.ProjectReportConfigsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportAchievementsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportChallengesRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportProjectMetricsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportStandardMetricsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportSystemMetricsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportsRecord
import com.terraformation.backend.db.accelerator.tables.references.REPORT_ACHIEVEMENTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_CHALLENGES
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.tables.records.PublishedReportAchievementsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportChallengesRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportPhotosRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportProjectMetricsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportStandardMetricsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportSystemMetricsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportsRecord
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.util.toInstant
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ReportStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()

  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }
  private val store: ReportStore by lazy {
    ReportStore(clock, dslContext, eventPublisher, reportsDao, systemUser)
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setup() {
    organizationId = insertOrganization(timeZone = ZoneOffset.UTC)
    projectId = insertProject()
    insertProjectAcceleratorDetails(dealName = "DEAL_Report Project")
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
              endDate = LocalDate.of(2030, Month.MARCH, 31),
              highlights = "highlights",
              internalComment = "internal comment",
              feedback = "feedback",
              additionalComments = "additional comments",
              financialSummaries = "financial summaries",
              createdBy = systemUser.userId,
              createdTime = Instant.ofEpochSecond(4000),
              modifiedBy = user.userId,
              modifiedTime = Instant.ofEpochSecond(8000),
              submittedBy = user.userId,
              submittedTime = Instant.ofEpochSecond(6000),
          )

      // These are sorted by positions regardless of insert order
      insertReportAchievement(position = 2, achievement = "Achievement C")
      insertReportAchievement(position = 0, achievement = "Achievement A")
      insertReportAchievement(position = 1, achievement = "Achievement B")

      insertReportChallenge(
          position = 1,
          challenge = "Challenge B",
          mitigationPlan = "Plan B",
      )
      insertReportChallenge(
          position = 0,
          challenge = "Challenge A",
          mitigationPlan = "Plan A",
      )

      val fileId1 = insertFile()
      insertReportPhoto(caption = "photo caption 1")

      val fileId2 = insertFile()
      insertReportPhoto(caption = "photo caption 2")

      insertFile()
      insertReportPhoto(caption = "deleted", deleted = true)

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              projectDealName = "DEAL_Report Project",
              frequency = ReportFrequency.Quarterly,
              quarter = ReportQuarter.Q1,
              status = ReportStatus.NeedsUpdate,
              startDate = LocalDate.of(2030, Month.JANUARY, 1),
              endDate = LocalDate.of(2030, Month.MARCH, 31),
              highlights = "highlights",
              achievements = listOf("Achievement A", "Achievement B", "Achievement C"),
              challenges =
                  listOf(
                      ReportChallengeModel(challenge = "Challenge A", mitigationPlan = "Plan A"),
                      ReportChallengeModel(challenge = "Challenge B", mitigationPlan = "Plan B"),
                  ),
              internalComment = "internal comment",
              feedback = "feedback",
              additionalComments = "additional comments",
              financialSummaries = "financial summaries",
              photos =
                  listOf(
                      ReportPhotoModel(fileId = fileId1, caption = "photo caption 1"),
                      ReportPhotoModel(fileId = fileId2, caption = "photo caption 2"),
                  ),
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
    fun `includes metrics`() {
      val configId = insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted)

      val projectMetricId =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project Metric description",
              name = "Project Metric Name",
              reference = "2.0",
              type = MetricType.Activity,
          )

      insertReportProjectMetric(
          reportId = reportId,
          metricId = projectMetricId,
          target = 100,
          status = ReportMetricStatus.OnTrack,
          modifiedTime = Instant.ofEpochSecond(1500),
          modifiedBy = user.userId,
      )

      val projectMetrics =
          listOf(
              ReportProjectMetricModel(
                  metric =
                      ProjectMetricModel(
                          id = projectMetricId,
                          projectId = projectId,
                          component = MetricComponent.ProjectObjectives,
                          description = "Project Metric description",
                          isPublishable = true,
                          name = "Project Metric Name",
                          reference = "2.0",
                          type = MetricType.Activity,
                      ),
                  entry =
                      ReportMetricEntryModel(
                          target = 100,
                          status = ReportMetricStatus.OnTrack,
                          modifiedTime = Instant.ofEpochSecond(1500),
                          modifiedBy = user.userId,
                      ),
              ),
          )

      val standardMetricId1 =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Climate standard metric description",
              name = "Climate Standard Metric",
              reference = "2.1",
              type = MetricType.Activity,
          )

      val standardMetricId2 =
          insertStandardMetric(
              component = MetricComponent.Community,
              description = "Community metric description",
              name = "Community Metric",
              reference = "10.0",
              type = MetricType.Outcome,
          )

      val standardMetricId3 =
          insertStandardMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project objectives metric description",
              name = "Project Objectives Metric",
              reference = "2.0",
              type = MetricType.Impact,
          )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          target = 55,
          value = 45,
          underperformanceJustification = "Almost at target",
          progressNotes = "Not quite there yet",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          target = 25,
          status = ReportMetricStatus.Unlikely,
          modifiedTime = Instant.ofEpochSecond(1500),
          modifiedBy = user.userId,
      )

      val standardMetrics =
          listOf(
              // ordered by reference
              ReportStandardMetricModel(
                  metric =
                      StandardMetricModel(
                          id = standardMetricId3,
                          component = MetricComponent.ProjectObjectives,
                          description = "Project objectives metric description",
                          isPublishable = true,
                          name = "Project Objectives Metric",
                          reference = "2.0",
                          type = MetricType.Impact,
                      ),
                  // all fields are null because no target/value have been set yet
                  entry = ReportMetricEntryModel(),
              ),
              ReportStandardMetricModel(
                  metric =
                      StandardMetricModel(
                          id = standardMetricId1,
                          component = MetricComponent.Climate,
                          description = "Climate standard metric description",
                          isPublishable = true,
                          name = "Climate Standard Metric",
                          reference = "2.1",
                          type = MetricType.Activity,
                      ),
                  entry =
                      ReportMetricEntryModel(
                          target = 55,
                          value = 45,
                          underperformanceJustification = "Almost at target",
                          progressNotes = "Not quite there yet",
                          modifiedTime = Instant.ofEpochSecond(3000),
                          modifiedBy = user.userId,
                      ),
              ),
              ReportStandardMetricModel(
                  metric =
                      StandardMetricModel(
                          id = standardMetricId2,
                          component = MetricComponent.Community,
                          description = "Community metric description",
                          isPublishable = true,
                          name = "Community Metric",
                          reference = "10.0",
                          type = MetricType.Outcome,
                      ),
                  entry =
                      ReportMetricEntryModel(
                          target = 25,
                          status = ReportMetricStatus.Unlikely,
                          modifiedTime = Instant.ofEpochSecond(1500),
                          modifiedBy = user.userId,
                      ),
              ),
          )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.Seedlings,
          target = 1000,
          modifiedTime = Instant.ofEpochSecond(2500),
          modifiedBy = user.userId,
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SeedsCollected,
          target = 2000,
          systemValue = 1800,
          systemTime = Instant.ofEpochSecond(8000),
          modifiedTime = Instant.ofEpochSecond(500),
          modifiedBy = user.userId,
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.TreesPlanted,
          target = 600,
          systemValue = 300,
          systemTime = Instant.ofEpochSecond(7000),
          overrideValue = 800,
          status = ReportMetricStatus.Achieved,
          modifiedTime = Instant.ofEpochSecond(700),
          modifiedBy = user.userId,
      )

      // These are ordered by reference.
      val systemMetrics =
          listOf(
              ReportSystemMetricModel(
                  metric = SystemMetric.SeedsCollected,
                  entry =
                      ReportSystemMetricEntryModel(
                          target = 2000,
                          systemValue = 1800,
                          systemTime = Instant.ofEpochSecond(8000),
                          modifiedTime = Instant.ofEpochSecond(500),
                          modifiedBy = user.userId,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.HectaresPlanted,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 0,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.Seedlings,
                  entry =
                      ReportSystemMetricEntryModel(
                          target = 1000,
                          systemValue = 0,
                          modifiedTime = Instant.ofEpochSecond(2500),
                          modifiedBy = user.userId,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.TreesPlanted,
                  entry =
                      ReportSystemMetricEntryModel(
                          target = 600,
                          systemValue = 300,
                          systemTime = Instant.ofEpochSecond(7000),
                          overrideValue = 800,
                          status = ReportMetricStatus.Achieved,
                          modifiedTime = Instant.ofEpochSecond(700),
                          modifiedBy = user.userId,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.SpeciesPlanted,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 0,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.MortalityRate,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 0,
                      ),
              ),
          )

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              projectDealName = "DEAL_Report Project",
              frequency = ReportFrequency.Quarterly,
              quarter = ReportQuarter.Q1,
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              projectMetrics = projectMetrics,
              standardMetrics = standardMetrics,
              systemMetrics = systemMetrics,
          )

      assertEquals(listOf(reportModel), store.fetch(includeMetrics = true))
    }

    @Test
    fun `queries Terraware data for system metrics`() {
      insertProjectReportConfig()
      insertReport(
          status = ReportStatus.NotSubmitted,
          frequency = ReportFrequency.Quarterly,
          quarter = ReportQuarter.Q1,
          startDate = LocalDate.of(2025, Month.JANUARY, 1),
          endDate = LocalDate.of(2025, Month.MARCH, 31),
      )

      insertDataForSystemMetrics(
          reportStartDate = LocalDate.of(2025, Month.JANUARY, 1),
          reportEndDate = LocalDate.of(2025, Month.MARCH, 31),
      )

      assertEquals(
          listOf(
              ReportSystemMetricModel(
                  metric = SystemMetric.SeedsCollected,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 98,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.HectaresPlanted,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 60,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.Seedlings,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 83,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.TreesPlanted,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 27,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.SpeciesPlanted,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 1,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.MortalityRate,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 40,
                      ),
              ),
          ),
          store.fetch(includeFuture = true, includeMetrics = true).first().systemMetrics,
      )
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
              projectDealName = "DEAL_Report Project",
              frequency = ReportFrequency.Quarterly,
              quarter = ReportQuarter.Q1,
              status = ReportStatus.NotNeeded,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
          )

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
              projectDealName = "DEAL_Report Project",
              frequency = ReportFrequency.Quarterly,
              quarter = ReportQuarter.Q1,
              status = ReportStatus.NotSubmitted,
              startDate = today,
              endDate = today.plusDays(31),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
          )

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
              projectDealName = "DEAL_Report Project",
              frequency = ReportFrequency.Quarterly,
              quarter = ReportQuarter.Q1,
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              internalComment = "internal comment",
          )

      assertEquals(
          listOf(reportModel.copy(internalComment = null)),
          store.fetch(),
          "Org user cannot see internal comment",
      )

      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      assertEquals(
          listOf(reportModel),
          store.fetch(),
          "Read-only Global role can see internal comment",
      )
    }

    @Test
    fun `filters by projectId or end year`() {
      val configId = insertProjectReportConfig()
      val reportId1 =
          insertReport(
              quarter = ReportQuarter.Q4,
              startDate = LocalDate.of(2030, Month.OCTOBER, 1),
              endDate = LocalDate.of(2030, Month.DECEMBER, 31),
          )
      val reportId2 =
          insertReport(
              quarter = ReportQuarter.Q4,
              startDate = LocalDate.of(2035, Month.OCTOBER, 1),
              endDate = LocalDate.of(2035, Month.DECEMBER, 31),
          )

      val otherProjectId = insertProject()
      val otherConfigId = insertProjectReportConfig()
      val otherReportId1 =
          insertReport(
              quarter = ReportQuarter.Q4,
              startDate = LocalDate.of(2035, Month.OCTOBER, 1),
              endDate = LocalDate.of(2035, Month.DECEMBER, 31),
          )
      val otherReportId2 =
          insertReport(
              quarter = ReportQuarter.Q4,
              startDate = LocalDate.of(2040, Month.OCTOBER, 1),
              endDate = LocalDate.of(2040, Month.DECEMBER, 31),
          )

      val reportModel1 =
          ReportModel(
              id = reportId1,
              configId = configId,
              projectId = projectId,
              projectDealName = "DEAL_Report Project",
              frequency = ReportFrequency.Quarterly,
              quarter = ReportQuarter.Q4,
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.of(2030, Month.OCTOBER, 1),
              endDate = LocalDate.of(2030, Month.DECEMBER, 31),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
          )

      val reportModel2 =
          reportModel1.copy(
              id = reportId2,
              startDate = LocalDate.of(2035, Month.OCTOBER, 1),
              endDate = LocalDate.of(2035, Month.DECEMBER, 31),
          )

      val otherReportModel1 =
          reportModel2.copy(
              id = otherReportId1,
              configId = otherConfigId,
              projectId = otherProjectId,
              projectDealName = null,
          )

      val otherReportModel2 =
          otherReportModel1.copy(
              id = otherReportId2,
              startDate = LocalDate.of(2040, Month.OCTOBER, 1),
              endDate = LocalDate.of(2040, Month.DECEMBER, 31),
          )

      clock.instant = LocalDate.of(2041, Month.JANUARY, 1).atStartOfDay().toInstant(ZoneOffset.UTC)

      assertEquals(
          setOf(reportModel1, reportModel2, otherReportModel1, otherReportModel2),
          store.fetch().toSet(),
          "Fetches all",
      )

      assertEquals(
          setOf(reportModel1, reportModel2),
          store.fetch(projectId = projectId).toSet(),
          "Fetches by projectId",
      )

      assertEquals(
          setOf(reportModel2, otherReportModel1),
          store.fetch(year = 2035).toSet(),
          "Fetches by year",
      )

      assertEquals(
          listOf(reportModel2),
          store.fetch(projectId = projectId, year = 2035),
          "Fetches by projectId and year",
      )
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
              projectDealName = "DEAL_Report Project",
              quarter = ReportQuarter.Q1,
              frequency = ReportFrequency.Quarterly,
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
              projectDealName = null,
          )

      val otherReportModel =
          reportModel.copy(
              id = otherReportId,
              configId = otherConfigId,
              projectId = otherProjectId,
              projectDealName = null,
          )

      assertEquals(
          emptyList<ReportModel>(),
          store.fetch(),
          "User not in organizations cannot see the reports",
      )

      insertOrganizationUser(organizationId = organizationId, role = Role.Contributor)
      assertEquals(emptyList<ReportModel>(), store.fetch(), "Contributor cannot see the reports")

      insertOrganizationUser(organizationId = organizationId, role = Role.Manager)
      assertEquals(
          setOf(reportModel, secondReportModel),
          store.fetch().toSet(),
          "Manager can see project reports within the organization",
      )

      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertEquals(
          setOf(reportModel, secondReportModel, otherReportModel),
          store.fetch().toSet(),
          "Read-only admin user can see all project reports",
      )
    }
  }

  @Nested
  inner class FetchOne {
    @Test
    fun `throws exception if user is not an organization manager, or global role user`() {
      deleteOrganizationUser(organizationId = organizationId)
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      insertProjectReportConfig()
      val reportId =
          insertReport(
              quarter = ReportQuarter.Q4,
              startDate = LocalDate.of(2030, Month.OCTOBER, 1),
              endDate = LocalDate.of(2030, Month.DECEMBER, 31),
          )

      assertThrows<ReportNotFoundException>(message = "Not organization member or global user") {
        store.fetchOne(reportId)
      }

      insertOrganizationUser(role = Role.Contributor)
      assertThrows<ReportNotFoundException>(message = "Organization contributor") {
        store.fetchOne(reportId)
      }

      deleteOrganizationUser(organizationId = organizationId)
      insertOrganizationUser(role = Role.Manager)
      assertDoesNotThrow(message = "Organization manager") { store.fetchOne(reportId) }

      deleteOrganizationUser(organizationId = organizationId)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertDoesNotThrow(message = "Read-only global user") { store.fetchOne(reportId) }
    }

    @Test
    fun `returns report, with metrics optionally`() {
      val configId = insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted)

      val projectMetricId =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project Metric description",
              name = "Project Metric Name",
              reference = "2.0",
              type = MetricType.Activity,
          )

      insertReportProjectMetric(
          reportId = reportId,
          metricId = projectMetricId,
          target = 100,
          status = ReportMetricStatus.OnTrack,
          modifiedTime = Instant.ofEpochSecond(1500),
          modifiedBy = user.userId,
      )

      val projectMetrics =
          listOf(
              ReportProjectMetricModel(
                  metric =
                      ProjectMetricModel(
                          id = projectMetricId,
                          projectId = projectId,
                          component = MetricComponent.ProjectObjectives,
                          description = "Project Metric description",
                          isPublishable = true,
                          name = "Project Metric Name",
                          reference = "2.0",
                          type = MetricType.Activity,
                      ),
                  entry =
                      ReportMetricEntryModel(
                          target = 100,
                          status = ReportMetricStatus.OnTrack,
                          modifiedTime = Instant.ofEpochSecond(1500),
                          modifiedBy = user.userId,
                      ),
              ),
          )

      val standardMetricId1 =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Climate standard metric description",
              name = "Climate Standard Metric",
              reference = "2.1",
              type = MetricType.Activity,
          )

      val standardMetricId2 =
          insertStandardMetric(
              component = MetricComponent.Community,
              description = "Community metric description",
              isPublishable = false,
              name = "Community Metric",
              reference = "10.0",
              type = MetricType.Outcome,
          )

      val standardMetricId3 =
          insertStandardMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project objectives metric description",
              name = "Project Objectives Metric",
              reference = "2.0",
              type = MetricType.Impact,
          )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          target = 55,
          value = 45,
          underperformanceJustification = "Almost at target",
          progressNotes = "Not quite there yet",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          target = 25,
          status = ReportMetricStatus.Unlikely,
          modifiedTime = Instant.ofEpochSecond(1500),
          modifiedBy = user.userId,
      )

      val standardMetrics =
          listOf(
              // ordered by reference
              ReportStandardMetricModel(
                  metric =
                      StandardMetricModel(
                          id = standardMetricId3,
                          component = MetricComponent.ProjectObjectives,
                          description = "Project objectives metric description",
                          isPublishable = true,
                          name = "Project Objectives Metric",
                          reference = "2.0",
                          type = MetricType.Impact,
                      ),
                  // all fields are null because no target/value have been set yet
                  entry = ReportMetricEntryModel(),
              ),
              ReportStandardMetricModel(
                  metric =
                      StandardMetricModel(
                          id = standardMetricId1,
                          component = MetricComponent.Climate,
                          description = "Climate standard metric description",
                          isPublishable = true,
                          name = "Climate Standard Metric",
                          reference = "2.1",
                          type = MetricType.Activity,
                      ),
                  entry =
                      ReportMetricEntryModel(
                          target = 55,
                          value = 45,
                          underperformanceJustification = "Almost at target",
                          progressNotes = "Not quite there yet",
                          modifiedTime = Instant.ofEpochSecond(3000),
                          modifiedBy = user.userId,
                      ),
              ),
              ReportStandardMetricModel(
                  metric =
                      StandardMetricModel(
                          id = standardMetricId2,
                          component = MetricComponent.Community,
                          description = "Community metric description",
                          isPublishable = false,
                          name = "Community Metric",
                          reference = "10.0",
                          type = MetricType.Outcome,
                      ),
                  entry =
                      ReportMetricEntryModel(
                          target = 25,
                          status = ReportMetricStatus.Unlikely,
                          modifiedTime = Instant.ofEpochSecond(1500),
                          modifiedBy = user.userId,
                      ),
              ),
          )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.Seedlings,
          target = 1000,
          modifiedTime = Instant.ofEpochSecond(2500),
          modifiedBy = user.userId,
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SeedsCollected,
          target = 2000,
          systemValue = 1800,
          systemTime = Instant.ofEpochSecond(8000),
          modifiedTime = Instant.ofEpochSecond(500),
          modifiedBy = user.userId,
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.TreesPlanted,
          target = 600,
          systemValue = 300,
          systemTime = Instant.ofEpochSecond(7000),
          overrideValue = 800,
          status = ReportMetricStatus.Achieved,
          modifiedTime = Instant.ofEpochSecond(700),
          modifiedBy = user.userId,
      )

      // These are ordered by reference.
      val systemMetrics =
          listOf(
              ReportSystemMetricModel(
                  metric = SystemMetric.SeedsCollected,
                  entry =
                      ReportSystemMetricEntryModel(
                          target = 2000,
                          systemValue = 1800,
                          systemTime = Instant.ofEpochSecond(8000),
                          modifiedTime = Instant.ofEpochSecond(500),
                          modifiedBy = user.userId,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.HectaresPlanted,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 0,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.Seedlings,
                  entry =
                      ReportSystemMetricEntryModel(
                          target = 1000,
                          systemValue = 0,
                          modifiedTime = Instant.ofEpochSecond(2500),
                          modifiedBy = user.userId,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.TreesPlanted,
                  entry =
                      ReportSystemMetricEntryModel(
                          target = 600,
                          systemValue = 300,
                          systemTime = Instant.ofEpochSecond(7000),
                          overrideValue = 800,
                          status = ReportMetricStatus.Achieved,
                          modifiedTime = Instant.ofEpochSecond(700),
                          modifiedBy = user.userId,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.SpeciesPlanted,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 0,
                      ),
              ),
              ReportSystemMetricModel(
                  metric = SystemMetric.MortalityRate,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = 0,
                      ),
              ),
          )

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              projectDealName = "DEAL_Report Project",
              frequency = ReportFrequency.Quarterly,
              quarter = ReportQuarter.Q1,
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = Instant.EPOCH,
              projectMetrics = projectMetrics,
              standardMetrics = standardMetrics,
              systemMetrics = systemMetrics,
          )

      assertEquals(
          reportModel,
          store.fetchOne(reportId, includeMetrics = true),
          "Fetch one with metrics",
      )

      assertEquals(
          reportModel.copy(
              projectMetrics = emptyList(),
              standardMetrics = emptyList(),
              systemMetrics = emptyList(),
          ),
          store.fetchOne(reportId, includeMetrics = false),
          "Fetch one without metrics",
      )
    }
  }

  @Nested
  inner class ReviewReport {
    @Test
    fun `throws Access Denied Exception for non-TFExpert users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.Submitted)

      assertThrows<AccessDeniedException>(message = "Read-only Global Role") {
        store.reviewReport(
            reportId = reportId,
            status = ReportStatus.Approved,
            highlights = "highlights",
            feedback = "feedback",
            internalComment = "internal comment",
            additionalComments = "additional comments",
            financialSummaries = "financial summaries",
        )
      }

      deleteUserGlobalRole(role = GlobalRole.ReadOnly)
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      assertDoesNotThrow(message = "TF Expert Global Role") {
        store.reviewReport(
            reportId = reportId,
            status = ReportStatus.Approved,
            highlights = "highlights",
            feedback = "feedback",
            internalComment = "internal comment",
            additionalComments = "additional comments",
            financialSummaries = "financial summaries",
        )
      }
    }

    @Test
    fun `throws Illegal State Exception if updating status of NotSubmitted or NotNeeded Reports`() {
      insertProjectReportConfig()
      val notSubmittedReportId = insertReport(status = ReportStatus.NotSubmitted)
      val notNeededReportId = insertReport(status = ReportStatus.NotNeeded)

      assertThrows<IllegalStateException>(message = "Not Submitted to Approved") {
        store.reviewReport(
            reportId = notSubmittedReportId,
            status = ReportStatus.Approved,
            highlights = "highlights",
            feedback = "feedback",
            internalComment = "internal comment",
        )
      }
      assertThrows<IllegalStateException>(message = "Not Needed to Approved") {
        store.reviewReport(
            reportId = notNeededReportId,
            status = ReportStatus.Approved,
            highlights = "highlights",
            feedback = "feedback",
            internalComment = "internal comment",
        )
      }
      assertDoesNotThrow(message = "Unchanged statuses") {
        store.reviewReport(
            reportId = notSubmittedReportId,
            status = ReportStatus.NotSubmitted,
            feedback = "feedback",
            internalComment = "internal comment",
        )
        store.reviewReport(
            reportId = notNeededReportId,
            status = ReportStatus.NotNeeded,
            highlights = "highlights",
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
              highlights = "existing highlights",
              feedback = "existing feedback",
              internalComment = "existing internal comment",
              additionalComments = "existing additional comments",
              financialSummaries = "existing financial summaries",
              modifiedBy = otherUserId,
              modifiedTime = Instant.ofEpochSecond(3000),
          )

      insertReportAchievement(position = 0, achievement = "Existing Achievement A")
      insertReportAchievement(position = 2, achievement = "Existing Achievement C")
      insertReportAchievement(position = 1, achievement = "Existing Achievement B")

      insertReportChallenge(
          position = 1,
          challenge = "Existing Challenge B",
          mitigationPlan = "Existing Plan B",
      )
      insertReportChallenge(
          position = 0,
          challenge = "Existing Challenge A",
          mitigationPlan = "Existing Plan A",
      )

      val existingReport = reportsDao.fetchOneById(reportId)!!

      clock.instant = Instant.ofEpochSecond(20000)

      store.reviewReport(
          reportId = reportId,
          status = ReportStatus.NeedsUpdate,
          highlights = "new highlights",
          achievements =
              listOf(
                  "New Achievement Z",
                  "New Achievement Y",
              ),
          challenges =
              listOf(
                  ReportChallengeModel(
                      challenge = "New Challenge Z",
                      mitigationPlan = "New Plan Z",
                  ),
                  ReportChallengeModel(
                      challenge = "New Challenge X",
                      mitigationPlan = "New Plan X",
                  ),
                  ReportChallengeModel(
                      challenge = "New Challenge Y",
                      mitigationPlan = "New Plan Y",
                  ),
              ),
          feedback = "new feedback",
          internalComment = "new internal comment",
          additionalComments = "new additional comments",
          financialSummaries = "new financial summaries",
      )

      val updatedReport =
          existingReport.copy(
              statusId = ReportStatus.NeedsUpdate,
              highlights = "new highlights",
              feedback = "new feedback",
              internalComment = "new internal comment",
              additionalComments = "new additional comments",
              financialSummaries = "new financial summaries",
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )

      assertTableEquals(ReportsRecord(updatedReport), "Reports table")

      assertTableEquals(
          listOf(
              ReportAchievementsRecord(
                  reportId = reportId,
                  position = 0,
                  achievement = "New Achievement Z",
              ),
              ReportAchievementsRecord(
                  reportId = reportId,
                  position = 1,
                  achievement = "New Achievement Y",
              ),
          ),
          "Report achievements table",
      )

      assertTableEquals(
          listOf(
              ReportChallengesRecord(
                  reportId = reportId,
                  position = 0,
                  challenge = "New Challenge Z",
                  mitigationPlan = "New Plan Z",
              ),
              ReportChallengesRecord(
                  reportId = reportId,
                  position = 1,
                  challenge = "New Challenge X",
                  mitigationPlan = "New Plan X",
              ),
              ReportChallengesRecord(
                  reportId = reportId,
                  position = 2,
                  challenge = "New Challenge Y",
                  mitigationPlan = "New Plan Y",
              ),
          ),
          "Report achievements table",
      )
    }
  }

  @Nested
  inner class ReviewReportMetrics {
    @Test
    fun `throws exception for non-TFExpert users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.Submitted)

      assertThrows<AccessDeniedException>(message = "Read-only Global Role") {
        store.reviewReportMetrics(reportId = reportId)
      }

      deleteUserGlobalRole(role = GlobalRole.ReadOnly)
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      assertDoesNotThrow(message = "TF-Expert Global Role") {
        store.reviewReportMetrics(reportId = reportId)
      }
    }

    @Test
    fun `upserts values and internalComment for existing and non-existing report metric rows`() {
      val otherUserId = insertUser()

      val standardMetricId1 =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Climate standard metric description",
              name = "Climate Standard Metric",
              reference = "2.1",
              type = MetricType.Activity,
          )

      val standardMetricId2 =
          insertStandardMetric(
              component = MetricComponent.Community,
              description = "Community metric description",
              name = "Community Metric",
              reference = "10.0",
              type = MetricType.Outcome,
          )

      val standardMetricId3 =
          insertStandardMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project objectives metric description",
              name = "Project Objectives Metric",
              reference = "2.0",
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

      val projectMetricId =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project Metric description",
              name = "Project Metric Name",
              reference = "2.0",
              type = MetricType.Activity,
          )

      val configId = insertProjectReportConfig()
      val reportId =
          insertReport(
              status = ReportStatus.Submitted,
              createdBy = otherUserId,
              createdTime = Instant.ofEpochSecond(1500),
              submittedBy = otherUserId,
              submittedTime = Instant.ofEpochSecond(3000),
          )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          target = 55,
          value = 45,
          underperformanceJustification = "Existing metric 1 notes",
          status = ReportMetricStatus.OnTrack,
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          target = 30,
          value = null,
          underperformanceJustification = "Existing metric 2 notes",
          progressNotes = "Existing metric 2 internal comment",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SeedsCollected,
          target = 1000,
          systemValue = 1200,
          systemTime = Instant.ofEpochSecond(4000),
          underperformanceJustification = "Existing seeds collected metric notes",
          progressNotes = "Existing seeds collected metric internal comment",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SpeciesPlanted,
          target = 10,
          overrideValue = 15,
          systemValue = 12,
          systemTime = Instant.ofEpochSecond(5000),
          underperformanceJustification = "Existing species planted metric notes",
          progressNotes = "Existing species planted metric internal comment",
          status = ReportMetricStatus.Unlikely,
          modifiedTime = Instant.ofEpochSecond(5000),
          modifiedBy = user.userId,
      )

      // At this point, the report has entries for metric 1 and 2, no entry for metric 3 and 4
      clock.instant = Instant.ofEpochSecond(9000)

      // We add new entries for metric 2 and 3. Metric 1 and 4 are not modified
      store.reviewReportMetrics(
          reportId = reportId,
          standardMetricEntries =
              mapOf(
                  standardMetricId2 to
                      ReportMetricEntryModel(
                          target = 99,
                          value = 88,
                          underperformanceJustification = "New metric 2 notes",
                          progressNotes = "New metric 2 internal comment",
                          status = ReportMetricStatus.OnTrack,

                          // These fields are ignored
                          modifiedTime = Instant.EPOCH,
                          modifiedBy = UserId(99),
                      ),
                  standardMetricId3 to
                      ReportMetricEntryModel(
                          target = 50,
                          value = 45,
                          underperformanceJustification = "New metric 3 notes",
                          progressNotes = "New metric 3 internal comment",
                      ),
              ),
          systemMetricEntries =
              mapOf(
                  SystemMetric.SpeciesPlanted to
                      ReportMetricEntryModel(
                          target = 5,
                          value = 4,
                          status = null,
                          underperformanceJustification = "New species planted metric notes",
                          progressNotes = "New species planted metric internal comment",
                      ),
                  SystemMetric.TreesPlanted to
                      ReportMetricEntryModel(
                          target = 250,
                          value = 45,
                          status = ReportMetricStatus.Unlikely,
                          underperformanceJustification = "New trees planted metric notes",
                          progressNotes = "New trees planted metric internal comment",
                      ),
              ),
          projectMetricEntries =
              mapOf(
                  projectMetricId to
                      ReportMetricEntryModel(
                          target = 100,
                          value = 50,
                          underperformanceJustification = "Project metric notes",
                          progressNotes = "Project metric internal comment",
                      ),
              ),
      )

      assertTableEquals(
          listOf(
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId1,
                  target = 55,
                  value = 45,
                  statusId = ReportMetricStatus.OnTrack,
                  underperformanceJustification = "Existing metric 1 notes",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = otherUserId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId2,
                  target = 99,
                  value = 88,
                  statusId = ReportMetricStatus.OnTrack,
                  underperformanceJustification = "New metric 2 notes",
                  progressNotes = "New metric 2 internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId3,
                  target = 50,
                  value = 45,
                  underperformanceJustification = "New metric 3 notes",
                  progressNotes = "New metric 3 internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              // Standard metric 4 is not inserted since there was no updates
          ),
          "Reports standard metrics table",
      )

      assertTableEquals(
          listOf(
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SeedsCollected,
                  target = 1000,
                  systemValue = 1200,
                  systemTime = Instant.ofEpochSecond(4000),
                  underperformanceJustification = "Existing seeds collected metric notes",
                  progressNotes = "Existing seeds collected metric internal comment",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = user.userId,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SpeciesPlanted,
                  target = 5,
                  systemValue = 12,
                  systemTime = Instant.ofEpochSecond(5000),
                  overrideValue = 4,
                  statusId = null,
                  underperformanceJustification = "New species planted metric notes",
                  progressNotes = "New species planted metric internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.TreesPlanted,
                  target = 250,
                  overrideValue = 45,
                  statusId = ReportMetricStatus.Unlikely,
                  underperformanceJustification = "New trees planted metric notes",
                  progressNotes = "New trees planted metric internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
          ),
          "Reports system metrics table",
      )

      assertTableEquals(
          listOf(
              ReportProjectMetricsRecord(
                  reportId = reportId,
                  projectMetricId = projectMetricId,
                  target = 100,
                  value = 50,
                  underperformanceJustification = "Project metric notes",
                  progressNotes = "Project metric internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              )
          ),
          "Reports project metrics table",
      )

      assertTableEquals(
          ReportsRecord(
              id = reportId,
              configId = configId,
              projectId = projectId,
              reportFrequencyId = ReportFrequency.Quarterly,
              reportQuarterId = ReportQuarter.Q1,
              statusId = ReportStatus.Submitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = otherUserId,
              createdTime = Instant.ofEpochSecond(1500),
              submittedBy = otherUserId,
              submittedTime = Instant.ofEpochSecond(3000),
              // Modified time and modified by are updated
              modifiedBy = user.userId,
              modifiedTime = Instant.ofEpochSecond(9000),
          ),
          "Reports table",
      )
    }
  }

  @Nested
  inner class UpdateReport {
    @Test
    fun `throws exception for non-organization users and non-internal users`() {
      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted)
      deleteOrganizationUser()
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertThrows<AccessDeniedException> {
        store.updateReport(
            reportId = reportId,
            highlights = "Highlights",
            achievements = emptyList(),
            challenges = emptyList(),
        )
      }
    }

    @Test
    fun `throws exception for reports not in NotSubmitted or NeedsUpdate`() {
      insertProjectReportConfig()
      val notNeededReportId = insertReport(status = ReportStatus.NotNeeded)
      val submittedReportId = insertReport(status = ReportStatus.Submitted)
      val approvedReportId = insertReport(status = ReportStatus.Approved)

      listOf(notNeededReportId, submittedReportId, approvedReportId).forEach {
        assertThrows<IllegalStateException> {
          store.updateReport(
              reportId = it,
              highlights = "Highlights",
              achievements = emptyList(),
              challenges = emptyList(),
          )
        }
      }
    }

    @Test
    fun `updates highlights, additional comments and financial summaries, merges new achievements and challenges rows`() {
      insertProjectReportConfig()
      val reportId =
          insertReport(
              highlights = "Existing Highlights",
              additionalComments = "Existing Additional Comments",
              financialSummaries = "Existing Financial Summaries",
          )
      val existingReportRow = reportsDao.fetchOneById(reportId)!!

      insertReportAchievement(position = 0, achievement = "Existing Achievement A")
      insertReportAchievement(position = 2, achievement = "Existing Achievement C")
      insertReportAchievement(position = 1, achievement = "Existing Achievement B")

      insertReportChallenge(
          position = 1,
          challenge = "Existing Challenge B",
          mitigationPlan = "Existing Plan B",
      )
      insertReportChallenge(
          position = 0,
          challenge = "Existing Challenge A",
          mitigationPlan = "Existing Plan A",
      )

      clock.instant = Instant.ofEpochSecond(30000)

      store.updateReport(
          reportId = reportId,
          highlights = "New Highlights",
          achievements =
              listOf(
                  "New Achievement Z",
                  "New Achievement Y",
              ),
          challenges =
              listOf(
                  ReportChallengeModel(
                      challenge = "New Challenge Z",
                      mitigationPlan = "New Plan Z",
                  ),
                  ReportChallengeModel(
                      challenge = "New Challenge X",
                      mitigationPlan = "New Plan X",
                  ),
                  ReportChallengeModel(
                      challenge = "New Challenge Y",
                      mitigationPlan = "New Plan Y",
                  ),
              ),
          additionalComments = "New Additional Comments",
          financialSummaries = "New Financial Summaries",
      )

      assertTableEquals(
          listOf(
              ReportAchievementsRecord(
                  reportId = reportId,
                  position = 0,
                  achievement = "New Achievement Z",
              ),
              ReportAchievementsRecord(
                  reportId = reportId,
                  position = 1,
                  achievement = "New Achievement Y",
              ),
          ),
          "Report achievements table",
      )

      assertTableEquals(
          listOf(
              ReportChallengesRecord(
                  reportId = reportId,
                  position = 0,
                  challenge = "New Challenge Z",
                  mitigationPlan = "New Plan Z",
              ),
              ReportChallengesRecord(
                  reportId = reportId,
                  position = 1,
                  challenge = "New Challenge X",
                  mitigationPlan = "New Plan X",
              ),
              ReportChallengesRecord(
                  reportId = reportId,
                  position = 2,
                  challenge = "New Challenge Y",
                  mitigationPlan = "New Plan Y",
              ),
          ),
          "Report achievements table",
      )

      assertTableEquals(
          ReportsRecord(
              existingReportRow.copy(
                  highlights = "New Highlights",
                  additionalComments = "New Additional Comments",
                  financialSummaries = "New Financial Summaries",
                  modifiedTime = clock.instant,
                  modifiedBy = user.userId,
              )
          ),
          "Report table",
      )
    }

    @Test
    fun `sets null and deletes achievements and challenges rows for empty params`() {
      insertProjectReportConfig()
      val reportId =
          insertReport(
              highlights = "Existing Highlights",
              additionalComments = "Existing Additional Comments",
              financialSummaries = "Existing Financial Summaries",
          )
      val existingReportRow = reportsDao.fetchOneById(reportId)!!

      insertReportAchievement(position = 0, achievement = "Existing Achievement A")
      insertReportAchievement(position = 2, achievement = "Existing Achievement C")
      insertReportAchievement(position = 1, achievement = "Existing Achievement B")

      insertReportChallenge(
          position = 1,
          challenge = "Existing Challenge B",
          mitigationPlan = "Existing Plan B",
      )
      insertReportChallenge(
          position = 0,
          challenge = "Existing Challenge A",
          mitigationPlan = "Existing Plan A",
      )

      clock.instant = Instant.ofEpochSecond(30000)

      store.updateReport(
          reportId = reportId,
          highlights = null,
          additionalComments = null,
          financialSummaries = null,
          achievements = emptyList(),
          challenges = emptyList(),
      )

      assertTableEmpty(REPORT_ACHIEVEMENTS, "Report achievements table")
      assertTableEmpty(REPORT_CHALLENGES, "Report challenges table")

      assertTableEquals(
          ReportsRecord(
              existingReportRow.copy(
                  highlights = null,
                  additionalComments = null,
                  financialSummaries = null,
                  modifiedTime = clock.instant,
                  modifiedBy = user.userId,
              )
          ),
          "Report table",
      )
    }

    @Test
    fun `upserts values and targets for existing and non-existing report metric rows`() {
      val otherUserId = insertUser()
      val standardMetricId1 =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Climate standard metric description",
              name = "Climate Standard Metric",
              reference = "2.1",
              type = MetricType.Activity,
          )

      val standardMetricId2 =
          insertStandardMetric(
              component = MetricComponent.Community,
              description = "Community metric description",
              name = "Community Metric",
              reference = "10.0",
              type = MetricType.Outcome,
          )

      val standardMetricId3 =
          insertStandardMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project objectives metric description",
              name = "Project Objectives Metric",
              reference = "2.0",
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

      val projectMetricId =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project Metric description",
              name = "Project Metric Name",
              reference = "2.0",
              type = MetricType.Activity,
          )

      val configId = insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted, createdBy = otherUserId)

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          target = 55,
          value = 45,
          underperformanceJustification = "Existing metric 1 notes",
          status = ReportMetricStatus.OnTrack,
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          target = 30,
          value = null,
          underperformanceJustification = "Existing metric 2 notes",
          progressNotes = "Existing metric 2 internal comment",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SeedsCollected,
          target = 1000,
          systemValue = 1200,
          systemTime = Instant.ofEpochSecond(4000),
          underperformanceJustification = "Existing seeds collected metric notes",
          progressNotes = "Existing seeds collected metric internal comment",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SpeciesPlanted,
          target = 10,
          overrideValue = 15,
          systemValue = 12,
          systemTime = Instant.ofEpochSecond(5000),
          underperformanceJustification = "Existing species planted metric notes",
          progressNotes = "Existing species planted metric internal comment",
          status = ReportMetricStatus.Unlikely,
          modifiedTime = Instant.ofEpochSecond(5000),
          modifiedBy = user.userId,
      )

      // At this point, the report has entries for standard metric 1 and 2, and no entry for
      // standard metric 3 and 4

      clock.instant = Instant.ofEpochSecond(9000)

      // We add new entries for standard metric 2 and 3. Standard metric 1 and 4 are not modified.
      // We also add a new entry for project metric
      store.updateReport(
          reportId = reportId,
          highlights = null,
          achievements = emptyList(),
          challenges = emptyList(),
          standardMetricEntries =
              mapOf(
                  standardMetricId2 to
                      ReportMetricEntryModel(
                          target = 99,
                          value = 88,
                          underperformanceJustification = "New metric 2 notes",
                          status = ReportMetricStatus.OnTrack,

                          // These fields are ignored
                          progressNotes = "Not permitted to write internal comment",
                          modifiedTime = Instant.EPOCH,
                          modifiedBy = UserId(99),
                      ),
                  standardMetricId3 to
                      ReportMetricEntryModel(
                          target = 50,
                          value = null,
                          underperformanceJustification = "New metric 3 notes",
                      ),
              ),
          systemMetricEntries =
              mapOf(
                  SystemMetric.SpeciesPlanted to
                      ReportMetricEntryModel(
                          target = 5,
                          underperformanceJustification = "New species planted metric notes",
                          status = null,

                          // These fields are ignored
                          value = 4,
                          progressNotes = "New species planted metric internal comment",
                          modifiedTime = Instant.EPOCH,
                          modifiedBy = UserId(99),
                      ),
                  SystemMetric.TreesPlanted to
                      ReportMetricEntryModel(
                          target = 250,
                          underperformanceJustification = "New trees planted metric notes",
                          status = ReportMetricStatus.Unlikely,

                          // These fields are ignored
                          value = 45,
                          progressNotes = "New trees planted metric internal comment",
                          modifiedTime = Instant.EPOCH,
                          modifiedBy = UserId(99),
                      ),
              ),
          projectMetricEntries =
              mapOf(
                  projectMetricId to
                      ReportMetricEntryModel(
                          target = 100,
                          value = 50,
                          underperformanceJustification = "Project metric notes",
                      ),
              ),
      )

      assertTableEquals(
          listOf(
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId1,
                  target = 55,
                  value = 45,
                  statusId = ReportMetricStatus.OnTrack,
                  underperformanceJustification = "Existing metric 1 notes",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = otherUserId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId2,
                  target = 99,
                  value = 88,
                  statusId = ReportMetricStatus.OnTrack,
                  underperformanceJustification = "New metric 2 notes",
                  progressNotes = "Existing metric 2 internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId3,
                  target = 50,
                  underperformanceJustification = "New metric 3 notes",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              // Standard metric 4 is not inserted since there was no updates
          ),
          "Reports standard metrics table",
      )

      assertTableEquals(
          listOf(
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SeedsCollected,
                  target = 1000,
                  systemValue = 1200,
                  systemTime = Instant.ofEpochSecond(4000),
                  underperformanceJustification = "Existing seeds collected metric notes",
                  progressNotes = "Existing seeds collected metric internal comment",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = user.userId,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SpeciesPlanted,
                  target = 5,
                  systemValue = 12,
                  systemTime = Instant.ofEpochSecond(5000),
                  statusId = null,
                  overrideValue = 15,
                  underperformanceJustification = "New species planted metric notes",
                  progressNotes = "Existing species planted metric internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.TreesPlanted,
                  target = 250,
                  statusId = ReportMetricStatus.Unlikely,
                  underperformanceJustification = "New trees planted metric notes",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
          ),
          "Reports system metrics table",
      )

      assertTableEquals(
          listOf(
              ReportProjectMetricsRecord(
                  reportId = reportId,
                  projectMetricId = projectMetricId,
                  target = 100,
                  value = 50,
                  underperformanceJustification = "Project metric notes",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              )
          ),
          "Reports project metrics table",
      )

      assertTableEquals(
          ReportsRecord(
              id = reportId,
              configId = configId,
              projectId = projectId,
              reportFrequencyId = ReportFrequency.Quarterly,
              reportQuarterId = ReportQuarter.Q1,
              statusId = ReportStatus.NotSubmitted,
              startDate = LocalDate.EPOCH,
              endDate = LocalDate.EPOCH.plusDays(1),
              createdBy = otherUserId,
              createdTime = Instant.EPOCH,
              // Modified time and modified by are updated
              modifiedBy = user.userId,
              modifiedTime = Instant.ofEpochSecond(9000),
          ),
          "Reports table",
      )
    }
  }

  @Nested
  inner class UpdateProjectMetricTargets {
    @Test
    fun `throws exception for non-organization admin users`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)
      val metricId = insertProjectMetric()
      assertThrows<AccessDeniedException> {
        store.updateProjectMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = emptyMap(),
        )
      }
    }

    @Test
    fun `throws exception for non TF Experts for updating submitted reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      val metricId = insertProjectMetric()

      assertThrows<AccessDeniedException> {
        store.updateProjectMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = emptyMap(),
            updateSubmitted = true,
        )
      }
    }

    @Test
    fun `throws exception for reports in non-editable statuses`() {
      insertProjectReportConfig()
      val submittedReportId = insertReport(status = ReportStatus.Submitted)
      val approvedReportId = insertReport(status = ReportStatus.Approved)
      val notNeededReportId = insertReport(status = ReportStatus.NotNeeded)

      val metricId = insertProjectMetric()

      assertThrows<IllegalStateException>("Submitted Report") {
        store.updateProjectMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = mapOf(submittedReportId to 0),
        )
      }

      assertThrows<IllegalStateException>("Approved Report") {
        store.updateProjectMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = mapOf(approvedReportId to 0),
        )
      }

      assertThrows<IllegalStateException>("Not Needed Report") {
        store.updateProjectMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = mapOf(notNeededReportId to 0),
        )
      }

      assertDoesNotThrow("Update submitted flag on") {
        store.updateProjectMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = mapOf(submittedReportId to 0, approvedReportId to 0, notNeededReportId to 0),
            updateSubmitted = true,
        )
      }
    }

    @Test
    fun `throws exception for reports not in projects`() {
      insertProjectReportConfig()
      val reportId = insertReport()
      val metricId = insertProjectMetric()

      insertProject()
      insertProjectReportConfig()
      val otherReportId = insertReport()

      assertThrows<IllegalStateException> {
        store.updateProjectMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = mapOf(reportId to 0, otherReportId to 0),
        )
      }

      assertDoesNotThrow {
        store.updateProjectMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = mapOf(reportId to 0),
        )
      }
    }

    @Test
    fun `upserts report targets`() {
      insertProjectReportConfig()
      val reportId1 = insertReport()
      val reportId2 = insertReport()
      val reportId3 = insertReport()
      val reportId4 = insertReport()

      val metricId = insertProjectMetric()

      insertReportProjectMetric(
          reportId = reportId1,
          metricId = metricId,
          target = 100,
          value = 100,
          status = ReportMetricStatus.Achieved,
          progressNotes = "Existing notes",
      )

      insertReportProjectMetric(
          reportId = reportId3,
          metricId = metricId,
          target = 300,
      )

      insertReportProjectMetric(
          reportId = reportId4,
          metricId = metricId,
          target = 400,
      )

      clock.instant = Instant.ofEpochSecond(9000)
      store.updateProjectMetricTargets(
          projectId = projectId,
          metricId = metricId,
          targets =
              mapOf(
                  reportId1 to 101,
                  reportId2 to 201,
                  reportId3 to null,
              ),
      )

      assertTableEquals(
          listOf(
              ReportProjectMetricsRecord(
                  reportId = reportId1,
                  projectMetricId = metricId,
                  target = 101,
                  value = 100,
                  statusId = ReportMetricStatus.Achieved,
                  progressNotes = "Existing notes",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportProjectMetricsRecord(
                  reportId = reportId2,
                  projectMetricId = metricId,
                  target = 201,
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportProjectMetricsRecord(
                  reportId = reportId3,
                  projectMetricId = metricId,
                  target = null,
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportProjectMetricsRecord(
                  reportId = reportId4,
                  projectMetricId = metricId,
                  target = 400,
                  modifiedTime = Instant.EPOCH,
                  modifiedBy = user.userId,
              ),
          )
      )
    }
  }

  @Nested
  inner class UpdateStandardMetricTargets {
    @Test
    fun `throws exception for non-organization admin users`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)
      val metricId = insertStandardMetric()
      assertThrows<AccessDeniedException> {
        store.updateStandardMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = emptyMap(),
        )
      }
    }

    @Test
    fun `throws exception for non TF Experts for updating submitted reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      val metricId = insertStandardMetric()

      assertThrows<AccessDeniedException> {
        store.updateStandardMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = emptyMap(),
            updateSubmitted = true,
        )
      }
    }

    @Test
    fun `throws exception for reports in non-editable statuses`() {
      insertProjectReportConfig()
      val submittedReportId = insertReport(status = ReportStatus.Submitted)
      val approvedReportId = insertReport(status = ReportStatus.Approved)
      val notNeededReportId = insertReport(status = ReportStatus.NotNeeded)

      val metricId = insertStandardMetric()

      assertThrows<IllegalStateException>("Submitted Report") {
        store.updateStandardMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = mapOf(submittedReportId to 0),
        )
      }

      assertThrows<IllegalStateException>("Approved Report") {
        store.updateStandardMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = mapOf(approvedReportId to 0),
        )
      }

      assertThrows<IllegalStateException>("Not Needed Report") {
        store.updateStandardMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = mapOf(notNeededReportId to 0),
        )
      }

      assertDoesNotThrow("Update submitted flag on") {
        store.updateStandardMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = mapOf(submittedReportId to 0, approvedReportId to 0, notNeededReportId to 0),
            updateSubmitted = true,
        )
      }
    }

    @Test
    fun `throws exception for reports not in projects`() {
      insertProjectReportConfig()
      val reportId = insertReport()

      insertProject()
      insertProjectReportConfig()
      val otherReportId = insertReport()

      val metricId = insertStandardMetric()

      assertThrows<IllegalStateException> {
        store.updateStandardMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = mapOf(reportId to 0, otherReportId to 0),
        )
      }

      assertDoesNotThrow {
        store.updateStandardMetricTargets(
            projectId = projectId,
            metricId = metricId,
            targets = mapOf(reportId to 0),
        )
      }
    }

    @Test
    fun `upserts report targets`() {
      insertProjectReportConfig()
      val reportId1 = insertReport()
      val reportId2 = insertReport()
      val reportId3 = insertReport()
      val reportId4 = insertReport()

      val metricId = insertStandardMetric()

      insertReportStandardMetric(
          reportId = reportId1,
          metricId = metricId,
          target = 100,
          value = 100,
          status = ReportMetricStatus.Achieved,
          progressNotes = "Existing notes",
      )

      insertReportStandardMetric(
          reportId = reportId3,
          metricId = metricId,
          target = 300,
      )

      insertReportStandardMetric(
          reportId = reportId4,
          metricId = metricId,
          target = 400,
      )

      clock.instant = Instant.ofEpochSecond(9000)
      store.updateStandardMetricTargets(
          projectId = projectId,
          metricId = metricId,
          targets =
              mapOf(
                  reportId1 to 101,
                  reportId2 to 201,
                  reportId3 to null,
              ),
      )

      assertTableEquals(
          listOf(
              ReportStandardMetricsRecord(
                  reportId = reportId1,
                  standardMetricId = metricId,
                  target = 101,
                  value = 100,
                  statusId = ReportMetricStatus.Achieved,
                  progressNotes = "Existing notes",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId2,
                  standardMetricId = metricId,
                  target = 201,
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId3,
                  standardMetricId = metricId,
                  target = null,
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId4,
                  standardMetricId = metricId,
                  target = 400,
                  modifiedTime = Instant.EPOCH,
                  modifiedBy = user.userId,
              ),
          )
      )
    }
  }

  @Nested
  inner class UpdateSystemMetricTargets {
    @Test
    fun `throws exception for non-organization admin users`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)
      assertThrows<AccessDeniedException> {
        store.updateSystemMetricTargets(
            projectId = projectId,
            metric = SystemMetric.SeedsCollected,
            targets = emptyMap(),
        )
      }
    }

    @Test
    fun `throws exception for non TF Experts for updating submitted reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      assertThrows<AccessDeniedException> {
        store.updateSystemMetricTargets(
            projectId = projectId,
            metric = SystemMetric.SeedsCollected,
            targets = emptyMap(),
            updateSubmitted = true,
        )
      }
    }

    @Test
    fun `throws exception for reports in non-editable statuses`() {
      insertProjectReportConfig()
      val submittedReportId = insertReport(status = ReportStatus.Submitted)
      val approvedReportId = insertReport(status = ReportStatus.Approved)
      val notNeededReportId = insertReport(status = ReportStatus.NotNeeded)

      assertThrows<IllegalStateException>("Submitted Report") {
        store.updateSystemMetricTargets(
            projectId = projectId,
            metric = SystemMetric.SeedsCollected,
            targets = mapOf(submittedReportId to 0),
        )
      }

      assertThrows<IllegalStateException>("Approved Report") {
        store.updateSystemMetricTargets(
            projectId = projectId,
            metric = SystemMetric.SeedsCollected,
            targets = mapOf(approvedReportId to 0),
        )
      }

      assertThrows<IllegalStateException>("Not Needed Report") {
        store.updateSystemMetricTargets(
            projectId = projectId,
            metric = SystemMetric.SeedsCollected,
            targets = mapOf(notNeededReportId to 0),
        )
      }

      assertDoesNotThrow("Update submitted flag on") {
        store.updateSystemMetricTargets(
            projectId = projectId,
            metric = SystemMetric.SeedsCollected,
            targets = mapOf(submittedReportId to 0, approvedReportId to 0, notNeededReportId to 0),
            updateSubmitted = true,
        )
      }
    }

    @Test
    fun `throws exception for reports not in projects`() {
      insertProjectReportConfig()
      val reportId = insertReport()

      insertProject()
      insertProjectReportConfig()
      val otherReportId = insertReport()

      assertThrows<IllegalStateException> {
        store.updateSystemMetricTargets(
            projectId = projectId,
            metric = SystemMetric.SeedsCollected,
            targets = mapOf(reportId to 0, otherReportId to 0),
        )
      }

      assertDoesNotThrow {
        store.updateSystemMetricTargets(
            projectId = projectId,
            metric = SystemMetric.SeedsCollected,
            targets = mapOf(reportId to 0),
        )
      }
    }

    @Test
    fun `upserts report targets`() {
      insertProjectReportConfig()
      val reportId1 = insertReport()
      val reportId2 = insertReport()
      val reportId3 = insertReport()
      val reportId4 = insertReport()

      insertReportSystemMetric(
          reportId = reportId1,
          metric = SystemMetric.SeedsCollected,
          target = 100,
          overrideValue = 100,
          status = ReportMetricStatus.Achieved,
          progressNotes = "Existing notes",
      )

      insertReportSystemMetric(
          reportId = reportId3,
          metric = SystemMetric.SeedsCollected,
          target = 300,
      )

      insertReportSystemMetric(
          reportId = reportId4,
          metric = SystemMetric.SeedsCollected,
          target = 400,
      )

      clock.instant = Instant.ofEpochSecond(9000)
      store.updateSystemMetricTargets(
          projectId = projectId,
          metric = SystemMetric.SeedsCollected,
          targets =
              mapOf(
                  reportId1 to 101,
                  reportId2 to 201,
                  reportId3 to null,
              ),
      )

      assertTableEquals(
          listOf(
              ReportSystemMetricsRecord(
                  reportId = reportId1,
                  systemMetricId = SystemMetric.SeedsCollected,
                  target = 101,
                  overrideValue = 100,
                  statusId = ReportMetricStatus.Achieved,
                  progressNotes = "Existing notes",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId2,
                  systemMetricId = SystemMetric.SeedsCollected,
                  target = 201,
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId3,
                  systemMetricId = SystemMetric.SeedsCollected,
                  target = null,
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId4,
                  systemMetricId = SystemMetric.SeedsCollected,
                  target = 400,
                  modifiedTime = Instant.EPOCH,
                  modifiedBy = user.userId,
              ),
          )
      )
    }
  }

  @Nested
  inner class RefreshSystemMetricValues {
    @Test
    fun `throws Access Denied Exception for non-TFExpert users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.Submitted)

      assertThrows<AccessDeniedException>(message = "Read-only Global Role") {
        store.refreshSystemMetricValues(reportId, emptySet())
      }

      deleteUserGlobalRole(role = GlobalRole.ReadOnly)
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      assertDoesNotThrow(message = "TF-Expert Global Role") {
        store.refreshSystemMetricValues(reportId, emptySet())
      }
    }

    @Test
    fun `sets system and override values to null if report is not yet submitted`() {
      val otherUserId = insertUser()
      insertProjectReportConfig()
      val reportId =
          insertReport(
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.of(2025, Month.JANUARY, 1),
              endDate = LocalDate.of(2025, Month.MARCH, 31),
          )

      insertDataForSystemMetrics(
          reportStartDate = LocalDate.of(2025, Month.JANUARY, 1),
          reportEndDate = LocalDate.of(2025, Month.MARCH, 31),
      )

      insertReportSystemMetric(
          metric = SystemMetric.SeedsCollected,
          target = 80,
          systemValue = 1000,
          systemTime = Instant.ofEpochSecond(3000),
          overrideValue = 74,
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      insertReportSystemMetric(
          metric = SystemMetric.Seedlings,
          target = 60,
          systemValue = 2000,
          systemTime = Instant.ofEpochSecond(3000),
          overrideValue = 98,
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      insertReportSystemMetric(
          metric = SystemMetric.TreesPlanted,
          systemValue = 3000,
          systemTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      val existingReport = reportsDao.fetchOneById(reportId)!!

      clock.instant = Instant.ofEpochSecond(9000)
      store.refreshSystemMetricValues(
          reportId,
          setOf(SystemMetric.Seedlings, SystemMetric.TreesPlanted, SystemMetric.SpeciesPlanted),
      )

      assertTableEquals(
          listOf(
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SeedsCollected,
                  target = 80,
                  systemValue = 1000,
                  systemTime = Instant.ofEpochSecond(3000),
                  overrideValue = 74,
                  modifiedBy = otherUserId,
                  modifiedTime = Instant.ofEpochSecond(3000),
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.Seedlings,
                  target = 60,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.TreesPlanted,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SpeciesPlanted,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
          )
      )

      val updatedReport =
          existingReport.copy(
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )

      assertTableEquals(ReportsRecord(updatedReport))
    }

    @Test
    fun `inserts into or updates report system metrics table, sets override values to null`() {
      val otherUserId = insertUser()
      insertProjectReportConfig()
      val reportId =
          insertReport(
              status = ReportStatus.Submitted,
              startDate = LocalDate.of(2025, Month.JANUARY, 1),
              endDate = LocalDate.of(2025, Month.MARCH, 31),
          )

      insertDataForSystemMetrics(
          reportStartDate = LocalDate.of(2025, Month.JANUARY, 1),
          reportEndDate = LocalDate.of(2025, Month.MARCH, 31),
      )

      insertReportSystemMetric(
          metric = SystemMetric.SeedsCollected,
          target = 80,
          systemValue = 1000,
          systemTime = Instant.ofEpochSecond(3000),
          overrideValue = 74,
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      insertReportSystemMetric(
          metric = SystemMetric.Seedlings,
          target = 60,
          systemValue = 2000,
          systemTime = Instant.ofEpochSecond(3000),
          overrideValue = 98,
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      insertReportSystemMetric(
          metric = SystemMetric.TreesPlanted,
          systemValue = 3000,
          systemTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      val existingReport = reportsDao.fetchOneById(reportId)!!

      clock.instant = Instant.ofEpochSecond(9000)
      store.refreshSystemMetricValues(
          reportId,
          setOf(SystemMetric.Seedlings, SystemMetric.TreesPlanted, SystemMetric.SpeciesPlanted),
      )

      assertTableEquals(
          listOf(
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SeedsCollected,
                  target = 80,
                  systemValue = 1000,
                  systemTime = Instant.ofEpochSecond(3000),
                  overrideValue = 74,
                  modifiedBy = otherUserId,
                  modifiedTime = Instant.ofEpochSecond(3000),
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.Seedlings,
                  target = 60,
                  systemValue = 83,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.TreesPlanted,
                  systemValue = 27,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SpeciesPlanted,
                  systemValue = 1,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
          )
      )

      val updatedReport =
          existingReport.copy(
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )

      assertTableEquals(ReportsRecord(updatedReport))
    }
  }

  @Nested
  inner class SubmitReport {
    @Test
    fun `throws exception for non-organization users and non-internal users`() {
      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted)
      deleteOrganizationUser()
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertThrows<AccessDeniedException> { store.submitReport(reportId) }
    }

    @Test
    fun `throws exception for reports in Approved or NotNeeded`() {
      insertProjectReportConfig()
      val notNeededReportId = insertReport(status = ReportStatus.NotNeeded)
      val submittedReportId = insertReport(status = ReportStatus.Submitted)
      val approvedReportId = insertReport(status = ReportStatus.Approved)

      assertThrows<IllegalStateException> { store.submitReport(notNeededReportId) }
      assertThrows<IllegalStateException> { store.submitReport(submittedReportId) }
      assertThrows<IllegalStateException> { store.submitReport(approvedReportId) }
    }

    @Test
    fun `sets report to submitted status, writes all system values, and publishes event`() {
      val configId = insertProjectReportConfig()
      val otherUserId = insertUser()
      val reportId =
          insertReport(
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.of(2025, Month.JANUARY, 1),
              endDate = LocalDate.of(2025, Month.MARCH, 31),
              createdBy = otherUserId,
              modifiedBy = otherUserId,
          )
      insertSystemMetricTargetsForReport(reportId)
      insertDataForSystemMetrics(
          reportStartDate = LocalDate.of(2025, Month.JANUARY, 1),
          reportEndDate = LocalDate.of(2025, Month.MARCH, 31),
      )

      clock.instant = Instant.ofEpochSecond(6000)
      store.submitReport(reportId)

      assertTableEquals(
          ReportsRecord(
              id = reportId,
              configId = configId,
              projectId = projectId,
              reportFrequencyId = ReportFrequency.Quarterly,
              reportQuarterId = ReportQuarter.Q1,
              statusId = ReportStatus.Submitted,
              startDate = LocalDate.of(2025, Month.JANUARY, 1),
              endDate = LocalDate.of(2025, Month.MARCH, 31),
              createdBy = otherUserId,
              createdTime = Instant.EPOCH,
              modifiedBy = otherUserId,
              modifiedTime = Instant.EPOCH,
              submittedBy = currentUser().userId,
              submittedTime = clock.instant,
          ),
          "Reports table",
      )

      assertTableEquals(
          listOf(
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SeedsCollected,
                  target = 0,
                  systemValue = 98,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.HectaresPlanted,
                  target = 0,
                  systemValue = 60,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.Seedlings,
                  target = 0,
                  systemValue = 83,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.TreesPlanted,
                  target = 0,
                  systemValue = 27,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  target = 0,
                  systemMetricId = SystemMetric.SpeciesPlanted,
                  systemValue = 1,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  target = 0,
                  systemMetricId = SystemMetric.MortalityRate,
                  systemValue = 40,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
          ),
          "Report system metrics",
      )

      eventPublisher.assertEventPublished(AcceleratorReportSubmittedEvent(reportId))
    }
  }

  @Nested
  inner class FetchProjectReportConfigs {
    @Test
    fun `throws exception for non global role users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      assertThrows<AccessDeniedException>(message = "No Global Role") {
        store.fetchProjectReportConfigs()
      }

      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertDoesNotThrow(message = "Read-only Global Role") { store.fetchProjectReportConfigs() }
    }

    @Test
    fun `queries by project, includes logframe URL`() {
      val otherProjectId = insertProject()
      insertProjectAcceleratorDetails(
          projectId = otherProjectId,
          logframeUrl = "https://terraware.io/logframe",
      )

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
              logframeUrl = null,
          )

      val projectConfigModel2 =
          ExistingProjectReportConfigModel(
              id = projectConfigId2,
              projectId = projectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.JANUARY, 7),
              reportingEndDate = LocalDate.of(2031, Month.MAY, 9),
              logframeUrl = null,
          )

      val otherProjectConfigModel1 =
          ExistingProjectReportConfigModel(
              id = otherProjectConfigId1,
              projectId = otherProjectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2027, Month.FEBRUARY, 6),
              reportingEndDate = LocalDate.of(2031, Month.JULY, 9),
              logframeUrl = URI("https://terraware.io/logframe"),
          )

      val otherProjectConfigModel2 =
          ExistingProjectReportConfigModel(
              id = otherProjectConfigId2,
              projectId = otherProjectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2031, Month.JANUARY, 18),
              reportingEndDate = LocalDate.of(2039, Month.MAY, 31),
              logframeUrl = URI("https://terraware.io/logframe"),
          )

      assertEquals(
          setOf(projectConfigModel1, projectConfigModel2),
          store.fetchProjectReportConfigs(projectId = projectId).toSet(),
          "fetches by projectId",
      )

      assertEquals(
          setOf(
              projectConfigModel1,
              projectConfigModel2,
              otherProjectConfigModel1,
              otherProjectConfigModel2,
          ),
          store.fetchProjectReportConfigs().toSet(),
          "fetches all project configs",
      )
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
              logframeUrl = null,
          )

      assertThrows<AccessDeniedException> { store.insertProjectReportConfig(config) }
    }

    @Test
    fun `inserts config record and creates reports for annual frequency`() {
      clock.instant = Instant.ofEpochSecond(9000)

      deleteProjectAcceleratorDetails(projectId)
      insertProjectAcceleratorDetails(
          projectId = projectId,
          logframeUrl = URI("https://example.com/existing-logframe"),
      )

      val config =
          NewProjectReportConfigModel(
              id = null,
              projectId = projectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2028, Month.MARCH, 2),
              logframeUrl = URI("https://example.com/new-logframe"),
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
          "Project report config tables",
      )

      assertTableEquals(
          listOf(
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2025, Month.MAY, 5),
                  endDate = LocalDate.of(2025, Month.DECEMBER, 31),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
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
                  reportFrequencyId = ReportFrequency.Annual,
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
                  reportFrequencyId = ReportFrequency.Annual,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2028, Month.JANUARY, 1),
                  endDate = LocalDate.of(2028, Month.MARCH, 2),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
          ),
          "Reports table",
      )

      assertTableEquals(
          ProjectAcceleratorDetailsRecord(
              projectId = projectId,
              logframeUrl = URI("https://example.com/new-logframe"),
          ),
          "Project accelerator details table",
      )
    }

    @Test
    fun `inserts config record and creates reports for quarterly frequency`() {
      clock.instant = Instant.ofEpochSecond(9000)

      deleteProjectAcceleratorDetails(projectId)
      val config =
          NewProjectReportConfigModel(
              id = null,
              projectId = projectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2026, Month.MARCH, 29),
              logframeUrl = URI("https://example.com"),
          )

      store.insertProjectReportConfig(config)

      val configId = projectReportConfigsDao.fetchByProjectId(projectId).single().id

      assertTableEquals(
          ProjectReportConfigsRecord(
              id = configId,
              projectId = projectId,
              reportFrequencyId = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2026, Month.MARCH, 29),
          ),
          "Project report config tables",
      )

      assertTableEquals(
          listOf(
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q2,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2025, Month.MAY, 5),
                  endDate = LocalDate.of(2025, Month.JUNE, 30),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q3,
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
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q4,
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
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q1,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2026, Month.JANUARY, 1),
                  endDate = LocalDate.of(2026, Month.MARCH, 29),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
          ),
          "Reports table",
      )

      assertTableEquals(
          ProjectAcceleratorDetailsRecord(
              projectId = projectId,
              logframeUrl = URI("https://example.com"),
          ),
          "Project accelerator details table",
      )
    }
  }

  @Nested
  inner class UpdateProjectReportConfig {
    @Test
    fun `throws exception for non accelerator admin users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val configId = insertProjectReportConfig()

      assertThrows<AccessDeniedException> {
        store.updateProjectReportConfig(
            configId = configId,
            reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
            reportingEndDate = LocalDate.of(2026, Month.MARCH, 29),
            logframeUrl = null,
        )
      }

      assertThrows<AccessDeniedException> {
        store.updateProjectReportConfig(
            projectId = projectId,
            reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
            reportingEndDate = LocalDate.of(2026, Month.MARCH, 29),
            logframeUrl = null,
        )
      }
    }

    @Test
    fun `updates the dates of the first and last report`() {
      // In this scenario, a user adjusts the reporting period minimally, so no new reports are
      // required, and no existing reports need to be archived
      val config =
          NewProjectReportConfigModel(
              id = null,
              projectId = projectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2026, Month.MARCH, 29),
              logframeUrl = null,
          )

      store.insertProjectReportConfig(config)
      val configId = projectReportConfigsDao.fetchByProjectId(projectId).single().id!!

      // This should be unchanged
      val otherConfigId =
          insertProjectReportConfig(
              projectId = projectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2026, Month.MARCH, 29),
          )

      store.updateProjectReportConfig(
          configId = configId,
          reportingStartDate = LocalDate.of(2025, Month.APRIL, 4),
          reportingEndDate = LocalDate.of(2026, Month.FEBRUARY, 28),
          logframeUrl = null,
      )

      assertTableEquals(
          listOf(
              ProjectReportConfigsRecord(
                  id = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportingStartDate = LocalDate.of(2025, Month.APRIL, 4),
                  reportingEndDate = LocalDate.of(2026, Month.FEBRUARY, 28),
              ),
              ProjectReportConfigsRecord(
                  id = otherConfigId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
                  reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
                  reportingEndDate = LocalDate.of(2026, Month.MARCH, 29),
              ),
          ),
          "Project report config tables",
      )

      assertTableEquals(
          listOf(
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q2,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2025, Month.APRIL, 4),
                  endDate = LocalDate.of(2025, Month.JUNE, 30),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q3,
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
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q4,
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
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q1,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2026, Month.JANUARY, 1),
                  endDate = LocalDate.of(2026, Month.FEBRUARY, 28),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
          ),
          "Reports table",
      )
    }

    @Test
    fun `archives reports outside of date range, and creates reports for new date range`() {
      val configId =
          insertProjectReportConfig(
              projectId = projectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2021, Month.MARCH, 13),
              reportingEndDate = LocalDate.of(2024, Month.JULY, 9),
          )

      // Reports are added in random order

      val year1ReportId =
          insertReport(
              configId = configId,
              projectId = projectId,
              quarter = null,
              frequency = ReportFrequency.Annual,
              status = ReportStatus.Submitted,
              startDate = LocalDate.of(2021, Month.MARCH, 13),
              endDate = LocalDate.of(2021, Month.DECEMBER, 31),
              upcomingNotificationSentTime = Instant.EPOCH,
          )

      // This one remains unchanged, but will help catch any looping issues
      val year0ReportId =
          insertReport(
              configId = configId,
              projectId = projectId,
              quarter = null,
              frequency = ReportFrequency.Annual,
              status = ReportStatus.NotNeeded,
              startDate = LocalDate.of(2020, Month.MARCH, 13),
              endDate = LocalDate.of(2020, Month.MAY, 31),
          )

      val year4ReportId =
          insertReport(
              configId = configId,
              projectId = projectId,
              quarter = null,
              frequency = ReportFrequency.Annual,
              status = ReportStatus.NotNeeded,
              startDate = LocalDate.of(2024, Month.JANUARY, 1),
              endDate = LocalDate.of(2024, Month.JULY, 9),
              upcomingNotificationSentTime = Instant.EPOCH,
          )

      val year3ReportId =
          insertReport(
              configId = configId,
              projectId = projectId,
              quarter = null,
              frequency = ReportFrequency.Annual,
              status = ReportStatus.Approved,
              startDate = LocalDate.of(2023, Month.JANUARY, 1),
              endDate = LocalDate.of(2023, Month.DECEMBER, 31),
              upcomingNotificationSentTime = Instant.EPOCH,
          )

      // year 2 is missing, which can happen if report dates were changed previously

      clock.instant = Instant.ofEpochSecond(9000)

      store.updateProjectReportConfig(
          configId = configId,
          reportingStartDate = LocalDate.of(2022, Month.FEBRUARY, 14),
          reportingEndDate = LocalDate.of(2025, Month.MARCH, 17),
          logframeUrl = null,
      )

      val reportIdsByYear =
          reportsDao.fetchByConfigId(configId).associate { it.startDate!!.year to it.id }

      assertTableEquals(
          ProjectReportConfigsRecord(
              id = configId,
              projectId = projectId,
              reportFrequencyId = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2022, Month.FEBRUARY, 14),
              reportingEndDate = LocalDate.of(2025, Month.MARCH, 17),
          ),
          "Project report config tables",
      )

      assertTableEquals(
          listOf(
              ReportsRecord(
                  id = year0ReportId,
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
                  statusId = ReportStatus.NotNeeded,
                  startDate = LocalDate.of(2020, Month.MARCH, 13),
                  endDate = LocalDate.of(2020, Month.MAY, 31),
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
              ),
              ReportsRecord(
                  id = year1ReportId,
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
                  statusId = ReportStatus.NotNeeded,
                  startDate = LocalDate.of(2021, Month.MARCH, 13),
                  endDate = LocalDate.of(2021, Month.DECEMBER, 31),
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
                  upcomingNotificationSentTime = Instant.EPOCH,
              ),
              ReportsRecord(
                  id = reportIdsByYear[2022]!!,
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2022, Month.FEBRUARY, 14),
                  endDate = LocalDate.of(2022, Month.DECEMBER, 31),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              // This one is unmodified
              ReportsRecord(
                  id = year3ReportId,
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
                  statusId = ReportStatus.Approved,
                  startDate = LocalDate.of(2023, Month.JANUARY, 1),
                  endDate = LocalDate.of(2023, Month.DECEMBER, 31),
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
                  submittedBy = user.userId,
                  submittedTime = Instant.EPOCH,
                  upcomingNotificationSentTime = Instant.EPOCH,
              ),
              ReportsRecord(
                  id = year4ReportId,
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2024, Month.JANUARY, 1),
                  endDate = LocalDate.of(2024, Month.DECEMBER, 31),
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
                  // Notification time set to null to allow new notifications
                  upcomingNotificationSentTime = null,
              ),
              ReportsRecord(
                  id = reportIdsByYear[2025]!!,
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2025, Month.JANUARY, 1),
                  endDate = LocalDate.of(2025, Month.MARCH, 17),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
          ),
          "Reports table",
      )
    }

    @Test
    fun `archives all existing reports and creates new reports if dates do not overlap`() {
      deleteProjectAcceleratorDetails(projectId)
      val config =
          NewProjectReportConfigModel(
              id = null,
              projectId = projectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2021, Month.MARCH, 13),
              reportingEndDate = LocalDate.of(2022, Month.JULY, 9),
              logframeUrl = null,
          )

      clock.instant = Instant.ofEpochSecond(300)

      store.insertProjectReportConfig(config)
      val configId = projectReportConfigsDao.fetchByProjectId(projectId).single().id!!

      clock.instant = Instant.ofEpochSecond(900)

      store.updateProjectReportConfig(
          configId = configId,
          reportingStartDate = LocalDate.of(2023, Month.MARCH, 13),
          reportingEndDate = LocalDate.of(2024, Month.JULY, 9),
          logframeUrl = URI("https://example.com/new"),
      )

      assertTableEquals(
          listOf(
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
                  statusId = ReportStatus.NotNeeded,
                  startDate = LocalDate.of(2021, Month.MARCH, 13),
                  endDate = LocalDate.of(2021, Month.DECEMBER, 31),
                  createdBy = systemUser.userId,
                  createdTime = Instant.ofEpochSecond(300),
                  modifiedBy = systemUser.userId,
                  modifiedTime = Instant.ofEpochSecond(900),
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
                  statusId = ReportStatus.NotNeeded,
                  startDate = LocalDate.of(2022, Month.JANUARY, 1),
                  endDate = LocalDate.of(2022, Month.JULY, 9),
                  createdBy = systemUser.userId,
                  createdTime = Instant.ofEpochSecond(300),
                  modifiedBy = systemUser.userId,
                  modifiedTime = Instant.ofEpochSecond(900),
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2023, Month.MARCH, 13),
                  endDate = LocalDate.of(2023, Month.DECEMBER, 31),
                  createdBy = systemUser.userId,
                  createdTime = Instant.ofEpochSecond(900),
                  modifiedBy = systemUser.userId,
                  modifiedTime = Instant.ofEpochSecond(900),
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2024, Month.JANUARY, 1),
                  endDate = LocalDate.of(2024, Month.JULY, 9),
                  createdBy = systemUser.userId,
                  createdTime = Instant.ofEpochSecond(900),
                  modifiedBy = systemUser.userId,
                  modifiedTime = Instant.ofEpochSecond(900),
              ),
          )
      )

      assertTableEquals(
          ProjectAcceleratorDetailsRecord(
              projectId = projectId,
              logframeUrl = URI("https://example.com/new"),
          ),
          "Project accelerator details table",
      )
    }

    @Test
    fun `updates all configs by projectId`() {
      deleteProjectAcceleratorDetails(projectId)
      insertProjectAcceleratorDetails(
          projectId = projectId,
          dealName = "Unchanged deal name",
          logframeUrl = URI("https://example.com/existing"),
      )

      val quarterlyConfigId =
          insertProjectReportConfig(
              projectId = projectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2026, Month.MARCH, 29),
          )

      val annualConfigId =
          insertProjectReportConfig(
              projectId = projectId,
              frequency = ReportFrequency.Annual,
              reportingStartDate = LocalDate.of(2034, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2038, Month.MARCH, 29),
          )

      store.updateProjectReportConfig(
          projectId = projectId,
          reportingStartDate = LocalDate.of(2044, Month.MARCH, 13),
          reportingEndDate = LocalDate.of(2048, Month.JULY, 9),
          logframeUrl = URI("https://example.com/new"),
      )

      assertTableEquals(
          listOf(
              ProjectReportConfigsRecord(
                  id = quarterlyConfigId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportingStartDate = LocalDate.of(2044, Month.MARCH, 13),
                  reportingEndDate = LocalDate.of(2048, Month.JULY, 9),
              ),
              ProjectReportConfigsRecord(
                  id = annualConfigId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Annual,
                  reportingStartDate = LocalDate.of(2044, Month.MARCH, 13),
                  reportingEndDate = LocalDate.of(2048, Month.JULY, 9),
              ),
          ),
          "Project report config table",
      )

      assertTableEquals(
          ProjectAcceleratorDetailsRecord(
              projectId = projectId,
              dealName = "Unchanged deal name",
              logframeUrl = URI("https://example.com/new"),
          ),
          "Project accelerator details table",
      )
    }
  }

  @Nested
  inner class NotifyUpcomingReports {
    @Test
    fun `throws exception for non-system user`() {
      assertThrows<AccessDeniedException>(message = "accelerator admin") {
        store.notifyUpcomingReports()
      }

      assertDoesNotThrow(message = "system user") {
        systemUser.run { store.notifyUpcomingReports() }
      }
    }

    @Test
    fun `publishes event for all Not Submitted reports within 15 days that have not been notified`() {
      val today = LocalDate.of(2025, Month.MARCH, 20)

      clock.instant = today.toInstant(ZoneId.systemDefault())
      val annualConfigId = insertProjectReportConfig(frequency = ReportFrequency.Annual)
      val annualReportId =
          insertReport(
              status = ReportStatus.NotSubmitted,
              frequency = ReportFrequency.Annual,
              quarter = null,
              startDate = LocalDate.of(2025, Month.JANUARY, 1),
              endDate = LocalDate.of(2025, Month.MARCH, 31),
          )

      val configId = insertProjectReportConfig()
      val upcomingReportId =
          insertReport(
              status = ReportStatus.NotSubmitted,
              quarter = ReportQuarter.Q1,
              startDate = LocalDate.of(2025, Month.JANUARY, 1),
              endDate = LocalDate.of(2025, Month.MARCH, 31),
          )

      val overdueReportId =
          insertReport(
              status = ReportStatus.NotSubmitted,
              quarter = ReportQuarter.Q4,
              startDate = LocalDate.of(2024, Month.OCTOBER, 1),
              endDate = LocalDate.of(2024, Month.DECEMBER, 31),
          )

      val notifiedReportId =
          insertReport(
              status = ReportStatus.NotSubmitted,
              quarter = ReportQuarter.Q1,
              startDate = LocalDate.of(2025, Month.JANUARY, 1),
              endDate = LocalDate.of(2025, Month.MARCH, 31),
              upcomingNotificationSentTime = Instant.ofEpochSecond(15000),
          )

      val submittedReportId =
          insertReport(
              status = ReportStatus.Submitted,
              quarter = ReportQuarter.Q1,
              startDate = LocalDate.of(2025, Month.JANUARY, 1),
              endDate = LocalDate.of(2025, Month.MARCH, 31),
              submittedBy = currentUser().userId,
              submittedTime = Instant.ofEpochSecond(30000),
          )

      val notNeededReportId =
          insertReport(
              status = ReportStatus.NotNeeded,
              quarter = ReportQuarter.Q1,
              startDate = LocalDate.of(2025, Month.JANUARY, 1),
              endDate = LocalDate.of(2025, Month.MARCH, 31),
          )

      val futureReportId =
          insertReport(
              status = ReportStatus.NotSubmitted,
              quarter = ReportQuarter.Q2,
              startDate = LocalDate.of(2025, Month.APRIL, 1),
              endDate = LocalDate.of(2025, Month.JUNE, 30),
          )

      systemUser.run { store.notifyUpcomingReports() }

      eventPublisher.assertExactEventsPublished(
          setOf(AcceleratorReportUpcomingEvent(upcomingReportId))
      )

      assertTableEquals(
          listOf(
              ReportsRecord(
                  id = annualReportId,
                  projectId = projectId,
                  configId = annualConfigId,
                  statusId = ReportStatus.NotSubmitted,
                  reportFrequencyId = ReportFrequency.Annual,
                  startDate = LocalDate.of(2025, Month.JANUARY, 1),
                  endDate = LocalDate.of(2025, Month.MARCH, 31),
                  createdBy = currentUser().userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = currentUser().userId,
                  modifiedTime = Instant.EPOCH,
              ),
              ReportsRecord(
                  id = upcomingReportId,
                  projectId = projectId,
                  configId = configId,
                  statusId = ReportStatus.NotSubmitted,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q1,
                  startDate = LocalDate.of(2025, Month.JANUARY, 1),
                  endDate = LocalDate.of(2025, Month.MARCH, 31),
                  createdBy = currentUser().userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = currentUser().userId,
                  modifiedTime = Instant.EPOCH,
                  upcomingNotificationSentTime = clock.instant,
              ),
              ReportsRecord(
                  id = overdueReportId,
                  projectId = projectId,
                  configId = configId,
                  statusId = ReportStatus.NotSubmitted,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q4,
                  startDate = LocalDate.of(2024, Month.OCTOBER, 1),
                  endDate = LocalDate.of(2024, Month.DECEMBER, 31),
                  createdBy = currentUser().userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = currentUser().userId,
                  modifiedTime = Instant.EPOCH,
              ),
              ReportsRecord(
                  id = notifiedReportId,
                  projectId = projectId,
                  configId = configId,
                  statusId = ReportStatus.NotSubmitted,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q1,
                  startDate = LocalDate.of(2025, Month.JANUARY, 1),
                  endDate = LocalDate.of(2025, Month.MARCH, 31),
                  createdBy = currentUser().userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = currentUser().userId,
                  modifiedTime = Instant.EPOCH,
                  upcomingNotificationSentTime = Instant.ofEpochSecond(15000),
              ),
              ReportsRecord(
                  id = submittedReportId,
                  projectId = projectId,
                  configId = configId,
                  statusId = ReportStatus.Submitted,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q1,
                  startDate = LocalDate.of(2025, Month.JANUARY, 1),
                  endDate = LocalDate.of(2025, Month.MARCH, 31),
                  createdBy = currentUser().userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = currentUser().userId,
                  modifiedTime = Instant.EPOCH,
                  submittedBy = currentUser().userId,
                  submittedTime = Instant.ofEpochSecond(30000),
              ),
              ReportsRecord(
                  id = notNeededReportId,
                  projectId = projectId,
                  configId = configId,
                  statusId = ReportStatus.NotNeeded,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q1,
                  startDate = LocalDate.of(2025, Month.JANUARY, 1),
                  endDate = LocalDate.of(2025, Month.MARCH, 31),
                  createdBy = currentUser().userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = currentUser().userId,
                  modifiedTime = Instant.EPOCH,
              ),
              ReportsRecord(
                  id = futureReportId,
                  projectId = projectId,
                  configId = configId,
                  statusId = ReportStatus.NotSubmitted,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q2,
                  startDate = LocalDate.of(2025, Month.APRIL, 1),
                  endDate = LocalDate.of(2025, Month.JUNE, 30),
                  createdBy = currentUser().userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = currentUser().userId,
                  modifiedTime = Instant.EPOCH,
              ),
          )
      )
    }
  }

  @Nested
  inner class PublishReport {
    private lateinit var reportId: ReportId
    private val standardMetricId1 by lazy { insertStandardMetric() }
    private val standardMetricId2 by lazy { insertStandardMetric() }
    private val standardMetricNullValueId by lazy { insertStandardMetric() }
    private val standardMetricNotPublishableId by lazy {
      insertStandardMetric(isPublishable = false)
    }

    private val projectMetricId1 by lazy { insertProjectMetric() }
    private val projectMetricId2 by lazy { insertProjectMetric() }
    private val projectMetricNullValueId by lazy { insertProjectMetric() }
    private val projectMetricNotPublishableId by lazy { insertProjectMetric(isPublishable = false) }

    private val fileId1 by lazy { insertFile() }
    private val fileId2 by lazy { insertFile() }

    @Test
    fun `throws exception for non-accelerator admin user`() {
      assertDoesNotThrow(message = "accelerator admin") { store.publishReport(reportId) }

      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      assertThrows<AccessDeniedException>(message = "TFExpert") { store.publishReport(reportId) }
    }

    @Test
    fun `throws exception if report is not yet approved`() {
      val notNeededReportId = insertReport(status = ReportStatus.NotNeeded)
      val notSubmittedReportId = insertReport(status = ReportStatus.NotSubmitted)
      val submittedReportId = insertReport(status = ReportStatus.Submitted)
      val needsUpdateReportId = insertReport(status = ReportStatus.NeedsUpdate)

      assertThrows<IllegalStateException>(message = "NotNeeded") {
        store.publishReport(notNeededReportId)
      }
      assertThrows<IllegalStateException>(message = "needsUpdateReportId") {
        store.publishReport(notSubmittedReportId)
      }
      assertThrows<IllegalStateException>(message = "Submitted") {
        store.publishReport(submittedReportId)
      }
      assertThrows<IllegalStateException>(message = "NeedsUpdate") {
        store.publishReport(needsUpdateReportId)
      }
    }

    @Test
    fun `inserts new rows for first publish`() {
      clock.instant = Instant.ofEpochSecond(10000)
      store.publishReport(reportId)
      assertPublishedReport(user.userId, clock.instant)

      eventPublisher.assertEventPublished(
          AcceleratorReportPublishedEvent(reportId),
          "Published Event",
      )
    }

    @Test
    fun `overwrites existing rows for subsequent publishes`() {
      insertPublishedReport(
          highlights = "Existing highlights",
          additionalComments = "Existing additional comments",
          financialSummaries = "Existing financial summaries",
      )

      insertPublishedReportAchievement(position = 0, achievement = "Existing Achievement A")

      insertPublishedReportAchievement(position = 25, achievement = "Existing Achievement Z")

      insertPublishedReportChallenge(
          position = 0,
          challenge = "Existing Challenge A",
          mitigationPlan = "Existing Plan A",
      )
      insertPublishedReportChallenge(
          position = 1,
          challenge = "Existing Challenge B",
          mitigationPlan = "Existing Plan B",
      )
      insertPublishedReportChallenge(
          position = 3,
          challenge = "Existing Challenge C",
          mitigationPlan = "Existing Plan C",
      )
      insertPublishedReportChallenge(
          position = 4,
          challenge = "Existing Challenge D",
          mitigationPlan = "Existing Plan D",
      )

      insertPublishedReportStandardMetric(
          metricId = standardMetricId1,
          target = 100,
          value = 100,
          progressNotes = "Existing progress notes",
          underperformanceJustification = "Existing underperformance justification",
      )

      insertPublishedReportStandardMetric(
          metricId = standardMetricNullValueId,
          target = 100,
          value = 100,
      )

      insertPublishedReportStandardMetric(
          metricId = standardMetricNotPublishableId,
          target = 100,
          value = 100,
      )

      insertPublishedReportProjectMetric(
          metricId = projectMetricId1,
          target = 100,
          value = 100,
          progressNotes = "Existing progress notes",
          underperformanceJustification = "Existing underperformance justification",
      )

      insertPublishedReportProjectMetric(
          metricId = projectMetricId2,
          target = 100,
          value = 100,
          underperformanceJustification = "Existing underperformance justification",
      )

      insertPublishedReportProjectMetric(
          metricId = projectMetricNotPublishableId,
          target = 100,
          value = 100,
      )

      insertPublishedReportSystemMetric(
          metric = SystemMetric.SeedsCollected,
          target = 100,
          value = 100,
      )

      insertPublishedReportSystemMetric(
          metric = SystemMetric.Seedlings,
          target = 100,
          value = 100,
          progressNotes = "Existing progress notes",
          underperformanceJustification = "Existing underperformance justification",
      )

      insertPublishedReportSystemMetric(
          metric = SystemMetric.TreesPlanted,
          target = 100,
          value = 100,
          underperformanceJustification = "Existing underperformance justification",
      )

      insertPublishedReportPhoto(
          fileId = fileId1,
          caption = "Old caption",
      )

      clock.instant = Instant.ofEpochSecond(10000)
      store.publishReport(reportId)
      assertPublishedReport(user.userId, clock.instant)
      eventPublisher.assertEventPublished(
          AcceleratorReportPublishedEvent(reportId),
          "Published Event",
      )
    }

    @BeforeEach
    fun setupReport() {
      insertProjectReportConfig()
      reportId =
          insertReport(
              status = ReportStatus.Approved,
              startDate = LocalDate.of(2030, Month.JANUARY, 1),
              endDate = LocalDate.of(2030, Month.MARCH, 31),
              highlights = "Highlights",
              internalComment = "Internal Comment",
              feedback = "Feedback",
              additionalComments = "Additional comments",
              financialSummaries = "Financial summaries",
              createdBy = systemUser.userId,
              createdTime = Instant.ofEpochSecond(4000),
              modifiedBy = user.userId,
              modifiedTime = Instant.ofEpochSecond(8000),
              submittedBy = user.userId,
              submittedTime = Instant.ofEpochSecond(6000),
          )

      insertReportAchievement(position = 0, achievement = "Achievement A")
      insertReportAchievement(position = 1, achievement = "Achievement B")
      insertReportAchievement(position = 2, achievement = "Achievement C")

      insertReportChallenge(
          position = 0,
          challenge = "Challenge A",
          mitigationPlan = "Plan A",
      )
      insertReportChallenge(
          position = 1,
          challenge = "Challenge B",
          mitigationPlan = "Plan B",
      )

      insertReportPhoto(reportId = reportId, fileId = fileId1, caption = "File Caption 1")
      insertReportPhoto(reportId = reportId, fileId = fileId2, caption = "File Caption 2")
      insertReportPhoto(
          reportId = reportId,
          fileId = insertFile(),
          caption = "Deleted File",
          deleted = true,
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          status = ReportMetricStatus.Achieved,
          target = 10,
          value = 10,
          underperformanceJustification = null,
          progressNotes = "Standard Metric 1 Progress notes",
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          status = ReportMetricStatus.OnTrack,
          target = 20,
          value = 19,
          underperformanceJustification = "Standard Metric 2 Underperformance",
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricNullValueId,
          target = 999,
          value = null,
      )

      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricNotPublishableId,
          target = 999,
          value = 999,
      )

      insertReportProjectMetric(
          reportId = reportId,
          metricId = projectMetricId1,
          status = ReportMetricStatus.Achieved,
          target = 30,
          value = 30,
          underperformanceJustification = null,
          progressNotes = "Project Metric 1 Progress notes",
      )

      insertReportProjectMetric(
          reportId = reportId,
          metricId = projectMetricId2,
          status = ReportMetricStatus.Unlikely,
          target = 40,
          value = 39,
          underperformanceJustification = "Project Metric 2 Underperformance",
      )

      insertReportProjectMetric(
          reportId = reportId,
          metricId = projectMetricNullValueId,
          target = 999,
          value = null,
      )

      insertReportProjectMetric(
          reportId = reportId,
          metricId = projectMetricNotPublishableId,
          target = 999,
          value = 999,
      )

      // Seeds Collected is not publishable
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SeedsCollected,
          status = ReportMetricStatus.Achieved,
          target = 999,
          systemValue = 999,
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.Seedlings,
          status = ReportMetricStatus.OnTrack,
          target = 50,
          overrideValue = 49,
          systemValue = 39,
          underperformanceJustification = "Seedlings underperformance justification",
          progressNotes = "Seedlings progress notes",
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SpeciesPlanted,
          status = ReportMetricStatus.Achieved,
          target = 10,
          systemValue = 10,
      )

      // Metrics can be published even with no target set
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.TreesPlanted,
          status = ReportMetricStatus.Achieved,
          target = null,
          systemValue = 100,
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.MortalityRate,
          status = ReportMetricStatus.Unlikely,
          target = 0,
          systemValue = 50,
      )
    }

    // Helper function to validate the report from setupReport() is in the published reports tables
    private fun assertPublishedReport(publishedBy: UserId, publishedTime: Instant) {
      assertTableEquals(
          PublishedReportsRecord(
              reportId = reportId,
              projectId = projectId,
              reportFrequencyId = ReportFrequency.Quarterly,
              reportQuarterId = ReportQuarter.Q1,
              startDate = LocalDate.of(2030, Month.JANUARY, 1),
              endDate = LocalDate.of(2030, Month.MARCH, 31),
              highlights = "Highlights",
              additionalComments = "Additional comments",
              financialSummaries = "Financial summaries",
              publishedBy = publishedBy,
              publishedTime = publishedTime,
          ),
          "Published reports table",
      )

      assertTableEquals(
          listOf(
              PublishedReportAchievementsRecord(
                  reportId = reportId,
                  position = 0,
                  achievement = "Achievement A",
              ),
              PublishedReportAchievementsRecord(
                  reportId = reportId,
                  position = 1,
                  achievement = "Achievement B",
              ),
              PublishedReportAchievementsRecord(
                  reportId = reportId,
                  position = 2,
                  achievement = "Achievement C",
              ),
          ),
          "Published report achievements table",
      )

      assertTableEquals(
          listOf(
              PublishedReportChallengesRecord(
                  reportId = reportId,
                  position = 0,
                  challenge = "Challenge A",
                  mitigationPlan = "Plan A",
              ),
              PublishedReportChallengesRecord(
                  reportId = reportId,
                  position = 1,
                  challenge = "Challenge B",
                  mitigationPlan = "Plan B",
              ),
          ),
          "Published report challenges table",
      )

      assertTableEquals(
          listOf(
              PublishedReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId1,
                  statusId = ReportMetricStatus.Achieved,
                  target = 10,
                  value = 10,
                  underperformanceJustification = null,
                  progressNotes = "Standard Metric 1 Progress notes",
              ),
              PublishedReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId2,
                  statusId = ReportMetricStatus.OnTrack,
                  target = 20,
                  value = 19,
                  underperformanceJustification = "Standard Metric 2 Underperformance",
              ),
          ),
          "Published report standard metrics table",
      )

      assertTableEquals(
          listOf(
              PublishedReportProjectMetricsRecord(
                  reportId = reportId,
                  projectMetricId = projectMetricId1,
                  statusId = ReportMetricStatus.Achieved,
                  target = 30,
                  value = 30,
                  underperformanceJustification = null,
                  progressNotes = "Project Metric 1 Progress notes",
              ),
              PublishedReportProjectMetricsRecord(
                  reportId = reportId,
                  projectMetricId = projectMetricId2,
                  statusId = ReportMetricStatus.Unlikely,
                  target = 40,
                  value = 39,
                  underperformanceJustification = "Project Metric 2 Underperformance",
              ),
          ),
          "Published report project metrics table",
      )

      assertTableEquals(
          listOf(
              PublishedReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.Seedlings,
                  statusId = ReportMetricStatus.OnTrack,
                  target = 50,
                  value = 49,
                  underperformanceJustification = "Seedlings underperformance justification",
                  progressNotes = "Seedlings progress notes",
              ),
              PublishedReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SpeciesPlanted,
                  statusId = ReportMetricStatus.Achieved,
                  target = 10,
                  value = 10,
              ),
              PublishedReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.TreesPlanted,
                  statusId = ReportMetricStatus.Achieved,
                  target = null,
                  value = 100,
              ),
              PublishedReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.MortalityRate,
                  statusId = ReportMetricStatus.Unlikely,
                  target = 0,
                  value = 50,
              ),
              PublishedReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.HectaresPlanted,
                  statusId = null,
                  target = null,
                  value = 0,
              ),
          ),
          "Published report system metrics table",
      )

      assertTableEquals(
          listOf(
              PublishedReportPhotosRecord(
                  reportId = reportId,
                  fileId = fileId1,
                  caption = "File Caption 1",
              ),
              PublishedReportPhotosRecord(
                  reportId = reportId,
                  fileId = fileId2,
                  caption = "File Caption 2",
              ),
          ),
          "Published report photos table",
      )
    }
  }

  private fun getRandomDate(startDate: LocalDate, endDate: LocalDate): LocalDate {
    val startEpochDay = startDate.toEpochDay()
    val endEpochDay = endDate.toEpochDay()

    val randomDay = Random.nextLong(startEpochDay, endEpochDay + 1)

    return LocalDate.ofEpochDay(randomDay)
  }

  private fun insertDataForSystemMetrics(reportStartDate: LocalDate, reportEndDate: LocalDate) {
    val otherProjectId = insertProject()
    val facilityId1 = insertFacility()
    val facilityId2 = insertFacility()

    // Seeds Collected
    listOf(
            AccessionsRow(
                facilityId = facilityId1,
                projectId = projectId,
                collectedDate = getRandomDate(reportStartDate, reportEndDate),
                estSeedCount = 25,
                remainingQuantity = BigDecimal(25),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                stateId = AccessionState.Processing,
            ),
            // Used-up accession
            AccessionsRow(
                facilityId = facilityId1,
                projectId = projectId,
                collectedDate = getRandomDate(reportStartDate, reportEndDate),
                estSeedCount = 0,
                remainingQuantity = BigDecimal(0),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                stateId = AccessionState.UsedUp,
                totalWithdrawnCount = 35,
            ),
            // Weight-based accession
            AccessionsRow(
                facilityId = facilityId2,
                projectId = projectId,
                collectedDate = getRandomDate(reportStartDate, reportEndDate),
                estSeedCount = 32,
                remainingGrams = BigDecimal(32),
                remainingQuantity = BigDecimal(32),
                remainingUnitsId = SeedQuantityUnits.Grams,
                stateId = AccessionState.Processing,
                subsetCount = 10,
                subsetWeightGrams = BigDecimal(10),
                totalWithdrawnCount = 6,
                totalWithdrawnWeightGrams = BigDecimal(6),
                totalWithdrawnWeightUnitsId = SeedQuantityUnits.Grams,
                totalWithdrawnWeightQuantity = BigDecimal(6),
            ),
            // Outside of report date range
            AccessionsRow(
                facilityId = facilityId1,
                projectId = projectId,
                collectedDate = reportEndDate.plusDays(1),
                estSeedCount = 2500,
                remainingQuantity = BigDecimal(2500),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                stateId = AccessionState.Processing,
            ),
            // Different project
            AccessionsRow(
                facilityId = facilityId2,
                projectId = otherProjectId,
                collectedDate = getRandomDate(reportStartDate, reportEndDate),
                estSeedCount = 1500,
                remainingQuantity = BigDecimal(1500),
                remainingUnitsId = SeedQuantityUnits.Seeds,
                stateId = AccessionState.Processing,
            ),
        )
        .forEach { insertAccession(it) }

    val speciesId = insertSpecies()
    val otherSpeciesId = insertSpecies()

    val batchId1 =
        insertBatch(
            BatchesRow(
                facilityId = facilityId1,
                projectId = projectId,
                addedDate = getRandomDate(reportStartDate, reportEndDate),
                activeGrowthQuantity = 15,
                germinatingQuantity = 7,
                hardeningOffQuantity = 4,
                readyQuantity = 3,
                totalLost = 100,
                speciesId = speciesId,
            )
        )

    val batchId2 =
        insertBatch(
            BatchesRow(
                facilityId = facilityId2,
                projectId = projectId,
                addedDate = getRandomDate(reportStartDate, reportEndDate),
                activeGrowthQuantity = 4,
                germinatingQuantity = 3,
                hardeningOffQuantity = 1,
                readyQuantity = 2,
                totalLost = 100,
                speciesId = otherSpeciesId,
            )
        )

    // Other project
    val otherBatchId =
        insertBatch(
            BatchesRow(
                facilityId = facilityId1,
                projectId = otherProjectId,
                addedDate = getRandomDate(reportStartDate, reportEndDate),
                activeGrowthQuantity = 100,
                germinatingQuantity = 100,
                hardeningOffQuantity = 100,
                readyQuantity = 100,
                totalLost = 100,
                speciesId = speciesId,
            )
        )

    // Outside of date range
    val outdatedBatchId =
        insertBatch(
            BatchesRow(
                facilityId = facilityId2,
                projectId = projectId,
                addedDate = reportStartDate.minusDays(1),
                activeGrowthQuantity = 100,
                germinatingQuantity = 100,
                hardeningOffQuantity = 100,
                readyQuantity = 100,
                totalLost = 100,
                speciesId = speciesId,
            )
        )

    val outplantWithdrawalId1 =
        insertNurseryWithdrawal(
            purpose = WithdrawalPurpose.OutPlant,
            withdrawnDate = getRandomDate(reportStartDate, reportEndDate),
        )
    insertBatchWithdrawal(
        batchId = batchId1,
        withdrawalId = outplantWithdrawalId1,
        readyQuantityWithdrawn = 10,
    )

    // Not counted towards seedlings, but counted towards planting
    insertBatchWithdrawal(
        batchId = otherBatchId,
        withdrawalId = outplantWithdrawalId1,
        readyQuantityWithdrawn = 8,
    )
    insertBatchWithdrawal(
        batchId = outdatedBatchId,
        withdrawalId = outplantWithdrawalId1,
        readyQuantityWithdrawn = 9,
    )

    val outplantWithdrawalId2 =
        insertNurseryWithdrawal(
            purpose = WithdrawalPurpose.OutPlant,
            withdrawnDate = getRandomDate(reportStartDate, reportEndDate),
        )
    insertBatchWithdrawal(
        batchId = batchId1,
        withdrawalId = outplantWithdrawalId2,
        readyQuantityWithdrawn = 6,
    )

    // This will count towards the seedlings metric, but not the trees planted metric.
    // This includes two species, but does not count towards species planted.
    val futureWithdrawalId =
        insertNurseryWithdrawal(
            purpose = WithdrawalPurpose.OutPlant,
            withdrawnDate = reportEndDate.plusDays(1),
        )
    insertBatchWithdrawal(
        batchId = batchId1,
        withdrawalId = futureWithdrawalId,
        readyQuantityWithdrawn = 7,
    )
    insertBatchWithdrawal(
        batchId = batchId2,
        withdrawalId = futureWithdrawalId,
        readyQuantityWithdrawn = 2,
    )

    val otherWithdrawalId =
        insertNurseryWithdrawal(
            purpose = WithdrawalPurpose.Other,
            withdrawnDate = getRandomDate(reportStartDate, reportEndDate),
        )
    insertBatchWithdrawal(
        batchId = batchId1,
        withdrawalId = otherWithdrawalId,
        germinatingQuantityWithdrawn = 1,
        activeGrowthQuantityWithdrawn = 2,
        hardeningOffQuantityWithdrawn = 3,
    )
    insertBatchWithdrawal(
        batchId = batchId2,
        withdrawalId = otherWithdrawalId,
        germinatingQuantityWithdrawn = 4,
        activeGrowthQuantityWithdrawn = 3,
        hardeningOffQuantityWithdrawn = 2,
    )

    val deadWithdrawalId =
        insertNurseryWithdrawal(
            purpose = WithdrawalPurpose.Dead,
            withdrawnDate = getRandomDate(reportStartDate, reportEndDate),
        )
    insertBatchWithdrawal(
        batchId = batchId1,
        withdrawalId = deadWithdrawalId,
        germinatingQuantityWithdrawn = 6,
    )
    insertBatchWithdrawal(
        batchId = batchId2,
        withdrawalId = deadWithdrawalId,
        germinatingQuantityWithdrawn = 8,
    )

    // This will not be counted towards seedlings, to prevent double-counting
    val nurseryTransferWithdrawalId =
        insertNurseryWithdrawal(
            purpose = WithdrawalPurpose.NurseryTransfer,
            withdrawnDate = getRandomDate(reportStartDate, reportEndDate),
        )
    insertBatchWithdrawal(
        batchId = batchId1,
        withdrawalId = nurseryTransferWithdrawalId,
        readyQuantityWithdrawn = 100,
        germinatingQuantityWithdrawn = 100,
        activeGrowthQuantityWithdrawn = 100,
        hardeningOffQuantityWithdrawn = 100,
    )
    insertBatchWithdrawal(
        batchId = batchId2,
        withdrawalId = nurseryTransferWithdrawalId,
        readyQuantityWithdrawn = 100,
        germinatingQuantityWithdrawn = 100,
        activeGrowthQuantityWithdrawn = 100,
        hardeningOffQuantityWithdrawn = 100,
    )

    // These two will not be counted
    val undoDate = getRandomDate(reportStartDate, reportEndDate)
    val undoneWithdrawalId =
        insertNurseryWithdrawal(purpose = WithdrawalPurpose.OutPlant, withdrawnDate = undoDate)
    // An undo can happen any time in the future
    val undoWithdrawalId =
        insertNurseryWithdrawal(
            purpose = WithdrawalPurpose.Undo,
            undoesWithdrawalId = undoneWithdrawalId,
            withdrawnDate = reportEndDate.plusDays(1),
        )
    insertBatchWithdrawal(
        batchId = batchId1,
        withdrawalId = undoneWithdrawalId,
        readyQuantityWithdrawn = 100,
    )
    insertBatchWithdrawal(
        batchId = batchId2,
        withdrawalId = undoneWithdrawalId,
        readyQuantityWithdrawn = 100,
    )
    insertBatchWithdrawal(
        batchId = batchId1,
        withdrawalId = undoWithdrawalId,
        readyQuantityWithdrawn = -100,
    )
    insertBatchWithdrawal(
        batchId = batchId2,
        withdrawalId = undoWithdrawalId,
        readyQuantityWithdrawn = -100,
    )

    val pastPlantingCompletedDate = reportStartDate.minusDays(1)
    val plantingCompletedDate1 = getRandomDate(reportStartDate, reportEndDate)
    val plantingCompletedDate2 = getRandomDate(reportStartDate, reportEndDate)
    val futurePlantingCompletedDate = reportEndDate.plusDays(1)

    val plantingSiteId1 = insertPlantingSite(projectId = projectId, boundary = multiPolygon(1))
    val plantingSiteHistoryId1 = insertPlantingSiteHistory()
    insertPlantingZone()
    val plantingSubzoneId1 =
        insertPlantingSubzone(
            areaHa = BigDecimal(10),
            plantingCompletedTime = plantingCompletedDate1.atStartOfDay().toInstant(ZoneOffset.UTC),
        )
    insertPlantingSubzone(
        areaHa = BigDecimal(20),
        plantingCompletedTime = pastPlantingCompletedDate.atStartOfDay().toInstant(ZoneOffset.UTC),
    )
    // Not counted, not completed
    insertPlantingSubzone(
        areaHa = BigDecimal(1000),
        plantingCompletedTime = null,
    )
    // Not counted, after reporting period
    insertPlantingSubzone(
        areaHa = BigDecimal(3000),
        plantingCompletedTime =
            futurePlantingCompletedDate.atStartOfDay().toInstant(ZoneOffset.UTC),
    )

    val plantingSiteId2 = insertPlantingSite(projectId = projectId, boundary = multiPolygon(1))
    val plantingSiteHistoryId2 = insertPlantingSiteHistory()
    insertPlantingZone()
    insertPlantingSubzone(
        areaHa = BigDecimal(30),
        plantingCompletedTime = plantingCompletedDate2.atStartOfDay().toInstant(ZoneOffset.UTC),
    )

    val otherPlantingSiteId =
        insertPlantingSite(projectId = otherProjectId, boundary = multiPolygon(1))
    val otherPlantingSiteHistoryId = insertPlantingSiteHistory()
    insertPlantingZone()
    // Not counted, outside of project site
    val otherPlantingSubzoneId =
        insertPlantingSubzone(
            areaHa = BigDecimal(5000),
            plantingCompletedTime = plantingCompletedDate2.atStartOfDay().toInstant(ZoneOffset.UTC),
        )

    val deliveryId =
        insertDelivery(
            plantingSiteId = plantingSiteId1,
            withdrawalId = outplantWithdrawalId1,
        )
    insertPlanting(
        plantingSiteId = plantingSiteId1,
        plantingSubzoneId = plantingSubzoneId1,
        deliveryId = deliveryId,
        numPlants = 27, // This should match up with the number of seedlings withdrawn
    )

    // These two are not counted, in both tree planted and species planted
    val undoneDeliveryId =
        insertDelivery(
            plantingSiteId = plantingSiteId1,
            withdrawalId = undoneWithdrawalId,
        )
    insertPlanting(
        plantingSiteId = plantingSiteId1,
        plantingSubzoneId = plantingSubzoneId1,
        deliveryId = undoneDeliveryId,
        numPlants = 200,
    )
    val undoDeliveryId =
        insertDelivery(
            plantingSiteId = plantingSiteId1,
            withdrawalId = undoWithdrawalId,
        )
    insertPlanting(
        plantingSiteId = plantingSiteId1,
        plantingSubzoneId = plantingSubzoneId1,
        plantingTypeId = PlantingType.Undo,
        deliveryId = undoDeliveryId,
        numPlants = -200,
    )

    // Does not count towards trees or species planted, since planting site is outside of project
    val otherDeliveryId =
        insertDelivery(
            plantingSiteId = otherPlantingSiteId,
            withdrawalId = outplantWithdrawalId2,
        )
    insertPlanting(
        plantingSiteId = otherPlantingSiteId,
        plantingSubzoneId = otherPlantingSubzoneId,
        deliveryId = otherDeliveryId,
        numPlants = 6,
    )

    // Does not count, since the withdrawal date is not within the report date range
    val futureDeliveryId =
        insertDelivery(
            plantingSiteId = plantingSiteId1,
            withdrawalId = futureWithdrawalId,
        )
    insertPlanting(
        plantingSiteId = plantingSiteId1,
        plantingSubzoneId = plantingSubzoneId1,
        deliveryId = futureDeliveryId,
        numPlants = 9,
    )

    // Not the latest observation, so the number does not count towards mortality rate
    val observationDate = getRandomDate(reportStartDate, reportEndDate.minusDays(1))
    val site1OldObservationId =
        insertObservation(
            plantingSiteId = plantingSiteId1,
            plantingSiteHistoryId = plantingSiteHistoryId1,
            state = ObservationState.Completed,
            completedTime = observationDate.atStartOfDay().toInstant(ZoneOffset.UTC),
        )
    insertObservedSiteSpeciesTotals(
        observationId = site1OldObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 0,
        cumulativeDead = 1000,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1OldObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = otherSpeciesId,
        permanentLive = 0,
        cumulativeDead = 1000,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1OldObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Other,
        speciesName = "Other",
        permanentLive = 0,
        cumulativeDead = 1000,
    )

    val site1NewObservationId =
        insertObservation(
            plantingSiteId = plantingSiteId1,
            plantingSiteHistoryId = plantingSiteHistoryId1,
            state = ObservationState.Completed,
            completedTime = observationDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC),
        )
    insertObservedSiteSpeciesTotals(
        observationId = site1NewObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 6,
        cumulativeDead = 1,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1NewObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = otherSpeciesId,
        permanentLive = 11,
        cumulativeDead = 3,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1NewObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Other,
        speciesName = "Other",
        permanentLive = 6,
        cumulativeDead = 7,
    )
    // Unknown plants are not counted towards mortality rate
    insertObservedSiteSpeciesTotals(
        observationId = site1NewObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Unknown,
        permanentLive = 0,
        cumulativeDead = 1000,
    )

    // Latest observation before the reporting period counts towards mortality rate
    val site2ObservationId =
        insertObservation(
            plantingSiteId = plantingSiteId2,
            plantingSiteHistoryId = plantingSiteHistoryId2,
            state = ObservationState.Completed,
            completedTime = reportStartDate.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC),
        )
    insertObservedSiteSpeciesTotals(
        observationId = site2ObservationId,
        plantingSiteId = plantingSiteId2,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 7,
        cumulativeDead = 9,
    )

    // Planting sites not part of the project is never counted
    val otherSiteObservationId =
        insertObservation(
            plantingSiteId = otherPlantingSiteId,
            plantingSiteHistoryId = otherPlantingSiteHistoryId,
            state = ObservationState.Completed,
            completedTime =
                getRandomDate(reportStartDate, reportEndDate)
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC),
        )
    insertObservedSiteSpeciesTotals(
        observationId = otherSiteObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 0,
        cumulativeDead = 1000,
    )
    // Total plants: 50
    // Dead plants: 20
  }

  private fun insertSystemMetricTargetsForReport(reportId: ReportId) {
    SystemMetric.entries.forEach { metric ->
      insertReportSystemMetric(
          reportId = reportId,
          metric = metric,
          target = 0,
      )
    }
  }
}
