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
import com.terraformation.backend.accelerator.model.ReportProjectMetricTargetModel
import com.terraformation.backend.accelerator.model.ReportStandardMetricModel
import com.terraformation.backend.accelerator.model.ReportStandardMetricTargetModel
import com.terraformation.backend.accelerator.model.ReportSystemMetricEntryModel
import com.terraformation.backend.accelerator.model.ReportSystemMetricModel
import com.terraformation.backend.accelerator.model.ReportSystemMetricTargetModel
import com.terraformation.backend.accelerator.model.StandardMetricModel
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SimpleUserModel
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
import com.terraformation.backend.db.funder.tables.records.PublishedProjectMetricTargetsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportAchievementsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportChallengesRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportPhotosRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportProjectMetricsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportStandardMetricsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportSystemMetricsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedStandardMetricTargetsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedSystemMetricTargetsRecord
import com.terraformation.backend.db.nursery.WithdrawalPurpose
import com.terraformation.backend.db.nursery.tables.pojos.BatchesRow
import com.terraformation.backend.db.seedbank.AccessionState
import com.terraformation.backend.db.seedbank.SeedQuantityUnits
import com.terraformation.backend.db.seedbank.tables.pojos.AccessionsRow
import com.terraformation.backend.db.tracking.ObservationState
import com.terraformation.backend.db.tracking.PlantingType
import com.terraformation.backend.db.tracking.RecordedSpeciesCertainty
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.i18n.Messages
import com.terraformation.backend.multiPolygon
import com.terraformation.backend.tracking.db.ObservationResultsStore
import com.terraformation.backend.util.toInstant
import com.terraformation.backend.util.toPlantsPerHectare
import java.math.BigDecimal
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt
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
  private val messages = Messages()
  private val observationResultsStore: ObservationResultsStore by lazy {
    ObservationResultsStore(dslContext)
  }

  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }
  private val store: ReportStore by lazy {
    ReportStore(
        clock,
        dslContext,
        eventPublisher,
        messages,
        observationResultsStore,
        reportsDao,
        systemUser,
    )
  }

  private lateinit var organizationId: OrganizationId
  private lateinit var projectId: ProjectId

  private val sitesLiveSum = 6 + 11 + 6 + 7
  private val site1T0Density = 10 + 11 + 30 + 31 + 40 + 41 + 50 + 51
  private val site1T0DensityWithTemp = 10 + 11 + 100 + 110 + 30 + 31 + 40 + 41 + 50 + 51
  private val site2T0Density = 65

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
      val tfUser = insertUser()

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
              submittedBy = tfUser,
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
              createdByUser = SimpleUserModel(systemUser.userId, "Terraware System"),
              createdTime = Instant.ofEpochSecond(4000),
              modifiedBy = user.userId,
              modifiedByUser = SimpleUserModel(user.userId, "First Last"),
              modifiedTime = Instant.ofEpochSecond(8000),
              submittedBy = tfUser,
              submittedByUser = SimpleUserModel(tfUser, "First Last"),
              submittedTime = Instant.ofEpochSecond(6000),
          )

      clock.instant = LocalDate.of(2031, Month.JANUARY, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
      assertEquals(listOf(reportModel), store.fetch())
    }

    @Test
    fun `overwrites user names as needed`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      val deletedUser = insertUser(deletedTime = clock.instant, email = "deleted@gone.com")
      insertOrganizationUser(userId = deletedUser)

      insertProjectReportConfig()
      insertOrganization()
      val tfUser = insertUser()
      insertOrganizationUser(userId = tfUser)

      insertReport(
          status = ReportStatus.NeedsUpdate,
          startDate = LocalDate.of(2030, Month.JANUARY, 1),
          endDate = LocalDate.of(2030, Month.MARCH, 31),
          highlights = "highlights",
          internalComment = "internal comment",
          feedback = "feedback",
          additionalComments = "additional comments",
          financialSummaries = "financial summaries",
          createdBy = tfUser,
          createdTime = Instant.ofEpochSecond(4000),
          modifiedBy = systemUser.userId,
          modifiedTime = Instant.ofEpochSecond(8000),
          submittedBy = deletedUser,
          submittedTime = Instant.ofEpochSecond(6000),
      )

      clock.instant = LocalDate.of(2031, Month.JANUARY, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
      val actualModel = store.fetch().first()
      assertEquals(
          SimpleUserModel(tfUser, "Terraformation Team"),
          actualModel.createdByUser,
          "Should have changed deleted user to 'Terraformation Team'",
      )
      assertEquals(
          SimpleUserModel(deletedUser, "Former User"),
          actualModel.submittedByUser,
          "Should have changed tf user to 'Former User'",
      )
      assertEquals(
          SimpleUserModel(systemUser.userId, "Terraformation Team"),
          actualModel.modifiedByUser,
          "Should have changed system user to 'Terraformation Team'",
      )
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

      // Insert target into new target table (report end date is 1970-01-02, so year is 1970)
      insertProjectMetricTarget(projectMetricId = projectMetricId, year = 1970, target = 100)
      insertReportProjectMetric(
          reportId = reportId,
          metricId = projectMetricId,
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

      // Insert targets into new target tables
      insertStandardMetricTarget(standardMetricId = standardMetricId1, year = 1970, target = 55)
      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          value = 45,
          projectsComments = "Almost at target",
          progressNotes = "Not quite there yet",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertStandardMetricTarget(standardMetricId = standardMetricId2, year = 1970, target = 25)
      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
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
                          projectsComments = "Almost at target",
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

      insertSystemMetricTarget(metric = SystemMetric.Seedlings, year = 1970, target = 1000)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.Seedlings,
          modifiedTime = Instant.ofEpochSecond(2500),
          modifiedBy = user.userId,
      )

      insertSystemMetricTarget(metric = SystemMetric.SeedsCollected, year = 1970, target = 2000)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SeedsCollected,
          systemValue = 1800,
          systemTime = Instant.ofEpochSecond(8000),
          modifiedTime = Instant.ofEpochSecond(500),
          modifiedBy = user.userId,
      )

      insertSystemMetricTarget(metric = SystemMetric.TreesPlanted, year = 1970, target = 600)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.TreesPlanted,
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
                  metric = SystemMetric.SurvivalRate,
                  entry = ReportSystemMetricEntryModel(systemValue = null),
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
              createdByUser = SimpleUserModel(user.userId, "First Last"),
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedByUser = SimpleUserModel(user.userId, "First Last"),
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
                  metric = SystemMetric.SurvivalRate,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue =
                              (sitesLiveSum * 100.0 / (site1T0Density + site2T0Density))
                                  .roundToInt(),
                      ),
              ),
          ),
          store.fetch(includeFuture = true, includeMetrics = true).first().systemMetrics,
          "All metrics",
      )

      // check just survival rate with temp plots
      with(PLANTING_SITES) {
        dslContext.update(this).set(SURVIVAL_RATE_INCLUDES_TEMP_PLOTS, true).execute()
      }

      val systemMetrics =
          store.fetch(includeFuture = true, includeMetrics = true).first().systemMetrics

      assertEquals(
          ReportSystemMetricModel(
              metric = SystemMetric.SurvivalRate,
              entry =
                  ReportSystemMetricEntryModel(
                      systemValue =
                          (sitesLiveSum * 100.0 / (site1T0DensityWithTemp + site2T0Density))
                              .roundToInt(),
                  ),
          ),
          systemMetrics.find { it.metric == SystemMetric.SurvivalRate },
          "Should include temp plots in survival rate metric",
      )
    }

    @Test
    fun `project survival rate excludes planting sites that don't have survival rates`() {
      val startDate = LocalDate.of(2025, Month.APRIL, 1)
      val endDate = LocalDate.of(2025, Month.JUNE, 30)
      insertProjectReportConfig()
      insertReport(
          status = ReportStatus.NotSubmitted,
          frequency = ReportFrequency.Quarterly,
          quarter = ReportQuarter.Q2,
          startDate = startDate,
          endDate = endDate,
      )

      insertDataForSurvivalRates(startDate, endDate)

      val systemMetrics =
          store.fetch(includeFuture = true, includeMetrics = true).first().systemMetrics

      assertEquals(
          ReportSystemMetricModel(
              metric = SystemMetric.SurvivalRate,
              entry =
                  ReportSystemMetricEntryModel(
                      systemValue = ((5 + 6 + 7 + 8) * 100.0 / (10 + 11 + 12 + 13)).roundToInt(),
                  ),
          ),
          systemMetrics.find { it.metric == SystemMetric.SurvivalRate },
          "Project survival rate correctly excludes sites without a survival rate",
      )

      // check survival rate with temp plots
      with(PLANTING_SITES) {
        dslContext.update(this).set(SURVIVAL_RATE_INCLUDES_TEMP_PLOTS, true).execute()
      }
      val systemMetricsWithTemp =
          store.fetch(includeFuture = true, includeMetrics = true).first().systemMetrics

      assertEquals(
          ReportSystemMetricModel(
              metric = SystemMetric.SurvivalRate,
              entry =
                  ReportSystemMetricEntryModel(
                      systemValue =
                          ((11 + 12 + 13 + 14) * 100.0 / (10 + 11 + 12 + 13 + 20 + 21 + 22 + 23))
                              .roundToInt(),
                  ),
          ),
          systemMetricsWithTemp.find { it.metric == SystemMetric.SurvivalRate },
          "Project survival rate w/temp correctly excludes sites without a survival rate",
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
              createdByUser = SimpleUserModel(user.userId, "First Last"),
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedByUser = SimpleUserModel(user.userId, "First Last"),
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
              createdByUser = SimpleUserModel(user.userId, "First Last"),
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedByUser = SimpleUserModel(user.userId, "First Last"),
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
              createdByUser = SimpleUserModel(user.userId, "First Last"),
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedByUser = SimpleUserModel(user.userId, "First Last"),
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
              createdByUser = SimpleUserModel(user.userId, "First Last"),
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedByUser = SimpleUserModel(user.userId, "First Last"),
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

      assertSetEquals(
          setOf(reportModel1, reportModel2, otherReportModel1, otherReportModel2),
          store.fetch().toSet(),
          "Fetches all",
      )

      assertSetEquals(
          setOf(reportModel1, reportModel2),
          store.fetch(projectId = projectId).toSet(),
          "Fetches by projectId",
      )

      assertSetEquals(
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
              createdByUser = SimpleUserModel(user.userId, "First Last"),
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedByUser = SimpleUserModel(user.userId, "First Last"),
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
      assertSetEquals(
          setOf(reportModel, secondReportModel),
          store.fetch().toSet(),
          "Manager can see project reports within the organization",
      )

      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertSetEquals(
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

      insertProjectMetricTarget(projectMetricId = projectMetricId, year = 1970, target = 100)
      insertReportProjectMetric(
          reportId = reportId,
          metricId = projectMetricId,
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

      insertStandardMetricTarget(standardMetricId = standardMetricId1, year = 1970, target = 55)
      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          value = 45,
          projectsComments = "Almost at target",
          progressNotes = "Not quite there yet",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertStandardMetricTarget(standardMetricId = standardMetricId2, year = 1970, target = 25)
      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
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
                          projectsComments = "Almost at target",
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

      insertSystemMetricTarget(metric = SystemMetric.Seedlings, year = 1970, target = 1000)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.Seedlings,
          modifiedTime = Instant.ofEpochSecond(2500),
          modifiedBy = user.userId,
      )

      insertSystemMetricTarget(metric = SystemMetric.SeedsCollected, year = 1970, target = 2000)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SeedsCollected,
          systemValue = 1800,
          systemTime = Instant.ofEpochSecond(8000),
          modifiedTime = Instant.ofEpochSecond(500),
          modifiedBy = user.userId,
      )

      insertSystemMetricTarget(metric = SystemMetric.TreesPlanted, year = 1970, target = 600)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.TreesPlanted,
          systemValue = 300,
          systemTime = Instant.ofEpochSecond(7000),
          overrideValue = 800,
          status = ReportMetricStatus.Achieved,
          modifiedTime = Instant.ofEpochSecond(700),
          modifiedBy = user.userId,
      )

      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SurvivalRate,
          systemValue = null,
          systemTime = Instant.ofEpochSecond(9000),
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
                  metric = SystemMetric.SurvivalRate,
                  entry =
                      ReportSystemMetricEntryModel(
                          systemValue = null,
                          systemTime = Instant.ofEpochSecond(9000),
                          modifiedTime = Instant.ofEpochSecond(700),
                          modifiedBy = user.userId,
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
              createdByUser = SimpleUserModel(user.userId, "First Last"),
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedByUser = SimpleUserModel(user.userId, "First Last"),
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
  inner class FetchReportYears {
    @Test
    fun `returns null for no report config`() {
      assertNull(store.fetchProjectReportYears(projectId))
    }

    @Test
    fun `returns earliest and latest report config years`() {
      insertProjectReportConfig(
          frequency = ReportFrequency.Quarterly,
          reportingStartDate = LocalDate.of(2024, 1, 1),
          reportingEndDate = LocalDate.of(2028, 12, 1),
      )
      assertEquals(
          2024 to 2028,
          store.fetchProjectReportYears(projectId),
      )
    }

    @Test
    fun `throws Access Denied Exception for non-accelerator or non-org managers`() {
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)

      assertThrows<AccessDeniedException>(message = "Contributor") {
        store.fetchProjectReportYears(projectId)
      }

      insertUserGlobalRole(role = GlobalRole.ReadOnly)
      assertDoesNotThrow(message = "Read Only") { store.fetchProjectReportYears(projectId) }

      deleteOrganizationUser()
      deleteUserGlobalRole(role = GlobalRole.ReadOnly)
      insertOrganizationUser(role = Role.Manager)
      assertDoesNotThrow(message = "Org Manager") { store.fetchProjectReportYears(projectId) }
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

      insertStandardMetricTarget(standardMetricId = standardMetricId1, year = 1970, target = 55)
      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          value = 45,
          projectsComments = "Existing metric 1 notes",
          status = ReportMetricStatus.OnTrack,
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
      )

      insertStandardMetricTarget(standardMetricId = standardMetricId2, year = 1970, target = 30)
      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          value = null,
          projectsComments = "Existing metric 2 notes",
          progressNotes = "Existing metric 2 internal comment",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertSystemMetricTarget(metric = SystemMetric.SeedsCollected, year = 1970, target = 1000)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SeedsCollected,
          systemValue = 1200,
          systemTime = Instant.ofEpochSecond(4000),
          projectsComments = "Existing seeds collected metric notes",
          progressNotes = "Existing seeds collected metric internal comment",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertSystemMetricTarget(metric = SystemMetric.SpeciesPlanted, year = 1970, target = 10)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SpeciesPlanted,
          overrideValue = 15,
          systemValue = 12,
          systemTime = Instant.ofEpochSecond(5000),
          projectsComments = "Existing species planted metric notes",
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
                          value = 88,
                          projectsComments = "New metric 2 notes",
                          progressNotes = "New metric 2 internal comment",
                          status = ReportMetricStatus.OnTrack,

                          // These fields are ignored
                          modifiedTime = Instant.EPOCH,
                          modifiedBy = UserId(99),
                      ),
                  standardMetricId3 to
                      ReportMetricEntryModel(
                          value = 45,
                          projectsComments = "New metric 3 notes",
                          progressNotes = "New metric 3 internal comment",
                      ),
              ),
          systemMetricEntries =
              mapOf(
                  SystemMetric.SpeciesPlanted to
                      ReportMetricEntryModel(
                          value = 4,
                          status = null,
                          projectsComments = "New species planted metric notes",
                          progressNotes = "New species planted metric internal comment",
                      ),
                  SystemMetric.TreesPlanted to
                      ReportMetricEntryModel(
                          value = 45,
                          status = ReportMetricStatus.Unlikely,
                          projectsComments = "New trees planted metric notes",
                          progressNotes = "New trees planted metric internal comment",
                      ),
              ),
          projectMetricEntries =
              mapOf(
                  projectMetricId to
                      ReportMetricEntryModel(
                          value = 50,
                          projectsComments = "Project metric notes",
                          progressNotes = "Project metric internal comment",
                      ),
              ),
      )

      assertTableEquals(
          listOf(
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId1,
                  value = 45,
                  statusId = ReportMetricStatus.OnTrack,
                  projectsComments = "Existing metric 1 notes",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = otherUserId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId2,
                  value = 88,
                  statusId = ReportMetricStatus.OnTrack,
                  projectsComments = "New metric 2 notes",
                  progressNotes = "New metric 2 internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId3,
                  value = 45,
                  projectsComments = "New metric 3 notes",
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
                  systemValue = 1200,
                  systemTime = Instant.ofEpochSecond(4000),
                  projectsComments = "Existing seeds collected metric notes",
                  progressNotes = "Existing seeds collected metric internal comment",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = user.userId,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SpeciesPlanted,
                  systemValue = 12,
                  systemTime = Instant.ofEpochSecond(5000),
                  overrideValue = 4,
                  statusId = null,
                  projectsComments = "New species planted metric notes",
                  progressNotes = "New species planted metric internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.TreesPlanted,
                  overrideValue = 45,
                  statusId = ReportMetricStatus.Unlikely,
                  projectsComments = "New trees planted metric notes",
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
                  value = 50,
                  projectsComments = "Project metric notes",
                  progressNotes = "Project metric internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
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
              ),
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
              ),
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

      insertStandardMetricTarget(standardMetricId = standardMetricId1, year = 1970, target = 55)
      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          value = 45,
          projectsComments = "Existing metric 1 notes",
          status = ReportMetricStatus.OnTrack,
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
      )

      insertStandardMetricTarget(standardMetricId = standardMetricId2, year = 1970, target = 30)
      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          value = null,
          projectsComments = "Existing metric 2 notes",
          progressNotes = "Existing metric 2 internal comment",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertSystemMetricTarget(metric = SystemMetric.SeedsCollected, year = 1970, target = 1000)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SeedsCollected,
          systemValue = 1200,
          systemTime = Instant.ofEpochSecond(4000),
          projectsComments = "Existing seeds collected metric notes",
          progressNotes = "Existing seeds collected metric internal comment",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertSystemMetricTarget(metric = SystemMetric.SpeciesPlanted, year = 1970, target = 10)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SpeciesPlanted,
          overrideValue = 15,
          systemValue = 12,
          systemTime = Instant.ofEpochSecond(5000),
          projectsComments = "Existing species planted metric notes",
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
                          value = 88,
                          projectsComments = "New metric 2 notes",
                          status = ReportMetricStatus.OnTrack,

                          // These fields are ignored
                          progressNotes = "Not permitted to write internal comment",
                          modifiedTime = Instant.EPOCH,
                          modifiedBy = UserId(99),
                      ),
                  standardMetricId3 to
                      ReportMetricEntryModel(
                          value = null,
                          projectsComments = "New metric 3 notes",
                      ),
              ),
          systemMetricEntries =
              mapOf(
                  SystemMetric.SpeciesPlanted to
                      ReportMetricEntryModel(
                          projectsComments = "New species planted metric notes",
                          status = null,

                          // These fields are ignored
                          value = 4,
                          progressNotes = "New species planted metric internal comment",
                          modifiedTime = Instant.EPOCH,
                          modifiedBy = UserId(99),
                      ),
                  SystemMetric.TreesPlanted to
                      ReportMetricEntryModel(
                          projectsComments = "New trees planted metric notes",
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
                          value = 50,
                          projectsComments = "Project metric notes",
                      ),
              ),
      )

      assertTableEquals(
          listOf(
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId1,
                  value = 45,
                  statusId = ReportMetricStatus.OnTrack,
                  projectsComments = "Existing metric 1 notes",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = otherUserId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId2,
                  value = 88,
                  statusId = ReportMetricStatus.OnTrack,
                  projectsComments = "New metric 2 notes",
                  progressNotes = "Existing metric 2 internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId3,
                  projectsComments = "New metric 3 notes",
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
                  systemValue = 1200,
                  systemTime = Instant.ofEpochSecond(4000),
                  projectsComments = "Existing seeds collected metric notes",
                  progressNotes = "Existing seeds collected metric internal comment",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = user.userId,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SpeciesPlanted,
                  systemValue = 12,
                  systemTime = Instant.ofEpochSecond(5000),
                  statusId = null,
                  overrideValue = 15,
                  projectsComments = "New species planted metric notes",
                  progressNotes = "Existing species planted metric internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.TreesPlanted,
                  statusId = ReportMetricStatus.Unlikely,
                  projectsComments = "New trees planted metric notes",
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
                  value = 50,
                  projectsComments = "Project metric notes",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
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
          systemValue = 1000,
          systemTime = Instant.ofEpochSecond(3000),
          overrideValue = 74,
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      insertReportSystemMetric(
          metric = SystemMetric.Seedlings,
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
                  systemValue = 1000,
                  systemTime = Instant.ofEpochSecond(3000),
                  overrideValue = 74,
                  modifiedBy = otherUserId,
                  modifiedTime = Instant.ofEpochSecond(3000),
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.Seedlings,
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
          ),
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
          systemValue = 1000,
          systemTime = Instant.ofEpochSecond(3000),
          overrideValue = 74,
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      insertReportSystemMetric(
          metric = SystemMetric.Seedlings,
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
                  systemValue = 1000,
                  systemTime = Instant.ofEpochSecond(3000),
                  overrideValue = 74,
                  modifiedBy = otherUserId,
                  modifiedTime = Instant.ofEpochSecond(3000),
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.Seedlings,
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
          ),
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
                  systemValue = 98,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.HectaresPlanted,
                  systemValue = 60,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.Seedlings,
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
              ReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SurvivalRate,
                  systemValue =
                      (sitesLiveSum * 100.0 / (site1T0Density + site2T0Density)).roundToInt(),
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

      val projectConfigId =
          insertProjectReportConfig(
              projectId = projectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.JANUARY, 7),
              reportingEndDate = LocalDate.of(2031, Month.MAY, 9),
          )

      val otherProjectConfigId =
          insertProjectReportConfig(
              projectId = otherProjectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2031, Month.JANUARY, 18),
              reportingEndDate = LocalDate.of(2039, Month.MAY, 31),
          )

      val projectConfigModel =
          ExistingProjectReportConfigModel(
              id = projectConfigId,
              projectId = projectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.JANUARY, 7),
              reportingEndDate = LocalDate.of(2031, Month.MAY, 9),
              logframeUrl = null,
          )

      val otherProjectConfigModel =
          ExistingProjectReportConfigModel(
              id = otherProjectConfigId,
              projectId = otherProjectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2031, Month.JANUARY, 18),
              reportingEndDate = LocalDate.of(2039, Month.MAY, 31),
              logframeUrl = URI("https://terraware.io/logframe"),
          )

      assertSetEquals(
          setOf(projectConfigModel),
          store.fetchProjectReportConfigs(projectId = projectId).toSet(),
          "fetches by projectId",
      )

      assertSetEquals(
          setOf(
              projectConfigModel,
              otherProjectConfigModel,
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
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2028, Month.MARCH, 2),
              logframeUrl = null,
          )

      assertThrows<AccessDeniedException> { store.insertProjectReportConfig(config) }
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
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2021, Month.MARCH, 13),
              reportingEndDate = LocalDate.of(2021, Month.SEPTEMBER, 9),
          )

      // Reports are added in random order

      val q2ReportId =
          insertReport(
              configId = configId,
              projectId = projectId,
              quarter = ReportQuarter.Q2,
              frequency = ReportFrequency.Quarterly,
              status = ReportStatus.Submitted,
              startDate = LocalDate.of(2021, Month.APRIL, 1),
              endDate = LocalDate.of(2021, Month.APRIL, 30),
              upcomingNotificationSentTime = Instant.EPOCH,
          )

      // This one remains unchanged, but will help catch any looping issues
      val unchangedReportId =
          insertReport(
              configId = configId,
              projectId = projectId,
              quarter = ReportQuarter.Q1,
              frequency = ReportFrequency.Quarterly,
              status = ReportStatus.NotNeeded,
              startDate = LocalDate.of(2020, Month.MARCH, 13),
              endDate = LocalDate.of(2020, Month.MARCH, 31),
          )

      val q4ReportId =
          insertReport(
              configId = configId,
              projectId = projectId,
              quarter = ReportQuarter.Q4,
              frequency = ReportFrequency.Quarterly,
              status = ReportStatus.NotNeeded,
              startDate = LocalDate.of(2021, Month.OCTOBER, 1),
              endDate = LocalDate.of(2021, Month.OCTOBER, 9),
              upcomingNotificationSentTime = Instant.EPOCH,
          )

      val q1ReportId =
          insertReport(
              configId = configId,
              projectId = projectId,
              quarter = ReportQuarter.Q1,
              frequency = ReportFrequency.Quarterly,
              status = ReportStatus.Approved,
              startDate = LocalDate.of(2021, Month.JANUARY, 1),
              endDate = LocalDate.of(2021, Month.MARCH, 31),
              upcomingNotificationSentTime = Instant.EPOCH,
          )

      // Q3 is missing, which can happen if report dates were changed previously

      clock.instant = Instant.ofEpochSecond(9000)

      store.updateProjectReportConfig(
          configId = configId,
          reportingStartDate = LocalDate.of(2021, Month.FEBRUARY, 14),
          reportingEndDate = LocalDate.of(2021, Month.DECEMBER, 27),
          logframeUrl = null,
      )

      val allReports = reportsDao.fetchByConfigId(configId).sortedBy { it.startDate }
      val reportIdsByQuarter = allReports.associate { it.reportQuarterId!! to it.id!! }

      assertTableEquals(
          ProjectReportConfigsRecord(
              id = configId,
              projectId = projectId,
              reportFrequencyId = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2021, Month.FEBRUARY, 14),
              reportingEndDate = LocalDate.of(2021, Month.DECEMBER, 27),
          ),
          "Project report config tables",
      )

      assertTableEquals(
          listOf(
              ReportsRecord(
                  id = unchangedReportId,
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q1,
                  statusId = ReportStatus.NotNeeded,
                  startDate = LocalDate.of(2020, Month.MARCH, 13),
                  endDate = LocalDate.of(2020, Month.MARCH, 31),
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = user.userId,
                  modifiedTime = Instant.EPOCH,
              ),
              ReportsRecord(
                  id = q1ReportId,
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q1,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2021, Month.FEBRUARY, 14),
                  endDate = LocalDate.of(2021, Month.MARCH, 31),
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  id = q2ReportId,
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q2,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2021, Month.APRIL, 1),
                  endDate = LocalDate.of(2021, Month.JUNE, 30),
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  id = reportIdsByQuarter[ReportQuarter.Q3],
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q3,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2021, Month.JULY, 1),
                  endDate = LocalDate.of(2021, Month.SEPTEMBER, 30),
                  createdBy = systemUser.userId,
                  createdTime = clock.instant,
                  modifiedBy = systemUser.userId,
                  modifiedTime = clock.instant,
              ),
              ReportsRecord(
                  id = q4ReportId,
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q4,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2021, Month.OCTOBER, 1),
                  endDate = LocalDate.of(2021, Month.DECEMBER, 27),
                  createdBy = user.userId,
                  createdTime = Instant.EPOCH,
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
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2021, Month.MARCH, 13),
              reportingEndDate = LocalDate.of(2021, Month.APRIL, 9),
              logframeUrl = null,
          )

      clock.instant = Instant.ofEpochSecond(300)

      store.insertProjectReportConfig(config)
      val configId = projectReportConfigsDao.fetchByProjectId(projectId).single().id!!

      clock.instant = Instant.ofEpochSecond(900)

      store.updateProjectReportConfig(
          configId = configId,
          reportingStartDate = LocalDate.of(2023, Month.MARCH, 13),
          reportingEndDate = LocalDate.of(2023, Month.JULY, 9),
          logframeUrl = URI("https://example.com/new"),
      )

      assertTableEquals(
          listOf(
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q1,
                  statusId = ReportStatus.NotNeeded,
                  startDate = LocalDate.of(2021, Month.MARCH, 13),
                  endDate = LocalDate.of(2021, Month.MARCH, 31),
                  createdBy = systemUser.userId,
                  createdTime = Instant.ofEpochSecond(300),
                  modifiedBy = systemUser.userId,
                  modifiedTime = Instant.ofEpochSecond(900),
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q2,
                  statusId = ReportStatus.NotNeeded,
                  startDate = LocalDate.of(2021, Month.APRIL, 1),
                  endDate = LocalDate.of(2021, Month.APRIL, 9),
                  createdBy = systemUser.userId,
                  createdTime = Instant.ofEpochSecond(300),
                  modifiedBy = systemUser.userId,
                  modifiedTime = Instant.ofEpochSecond(900),
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q1,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2023, Month.MARCH, 13),
                  endDate = LocalDate.of(2023, Month.MARCH, 31),
                  createdBy = systemUser.userId,
                  createdTime = Instant.ofEpochSecond(900),
                  modifiedBy = systemUser.userId,
                  modifiedTime = Instant.ofEpochSecond(900),
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q2,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2023, Month.APRIL, 1),
                  endDate = LocalDate.of(2023, Month.JUNE, 30),
                  createdBy = systemUser.userId,
                  createdTime = Instant.ofEpochSecond(900),
                  modifiedBy = systemUser.userId,
                  modifiedTime = Instant.ofEpochSecond(900),
              ),
              ReportsRecord(
                  configId = configId,
                  projectId = projectId,
                  reportFrequencyId = ReportFrequency.Quarterly,
                  reportQuarterId = ReportQuarter.Q3,
                  statusId = ReportStatus.NotSubmitted,
                  startDate = LocalDate.of(2023, Month.JULY, 1),
                  endDate = LocalDate.of(2023, Month.JULY, 9),
                  createdBy = systemUser.userId,
                  createdTime = Instant.ofEpochSecond(900),
                  modifiedBy = systemUser.userId,
                  modifiedTime = Instant.ofEpochSecond(900),
              ),
          ),
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

      val configId =
          insertProjectReportConfig(
              projectId = projectId,
              frequency = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2026, Month.MARCH, 29),
          )

      store.updateProjectReportConfig(
          projectId = projectId,
          reportingStartDate = LocalDate.of(2044, Month.MARCH, 13),
          reportingEndDate = LocalDate.of(2048, Month.JULY, 9),
          logframeUrl = URI("https://example.com/new"),
      )

      assertTableEquals(
          ProjectReportConfigsRecord(
              id = configId,
              projectId = projectId,
              reportFrequencyId = ReportFrequency.Quarterly,
              reportingStartDate = LocalDate.of(2044, Month.MARCH, 13),
              reportingEndDate = LocalDate.of(2048, Month.JULY, 9),
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
          setOf(AcceleratorReportUpcomingEvent(upcomingReportId)),
      )

      assertTableEquals(
          listOf(
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
          ),
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

      insertStandardMetricTarget(standardMetricId = standardMetricId1, year = 2030, target = 10)
      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId1,
          status = ReportMetricStatus.Achieved,
          value = 10,
          projectsComments = null,
          progressNotes = "Standard Metric 1 Progress notes",
      )

      insertStandardMetricTarget(standardMetricId = standardMetricId2, year = 2030, target = 20)
      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricId2,
          status = ReportMetricStatus.OnTrack,
          value = 19,
          projectsComments = "Standard Metric 2 Underperformance",
      )

      insertStandardMetricTarget(
          standardMetricId = standardMetricNullValueId,
          year = 2030,
          target = 999,
      )
      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricNullValueId,
          value = null,
      )

      insertStandardMetricTarget(
          standardMetricId = standardMetricNotPublishableId,
          year = 2030,
          target = 999,
      )
      insertReportStandardMetric(
          reportId = reportId,
          metricId = standardMetricNotPublishableId,
          value = 999,
      )

      insertProjectMetricTarget(projectMetricId = projectMetricId1, year = 2030, target = 30)
      insertReportProjectMetric(
          reportId = reportId,
          metricId = projectMetricId1,
          status = ReportMetricStatus.Achieved,
          value = 30,
          projectsComments = null,
          progressNotes = "Project Metric 1 Progress notes",
      )

      insertProjectMetricTarget(projectMetricId = projectMetricId2, year = 2030, target = 40)
      insertReportProjectMetric(
          reportId = reportId,
          metricId = projectMetricId2,
          status = ReportMetricStatus.Unlikely,
          value = 39,
          projectsComments = "Project Metric 2 Underperformance",
      )

      insertProjectMetricTarget(
          projectMetricId = projectMetricNullValueId,
          year = 2030,
          target = 999,
      )
      insertReportProjectMetric(
          reportId = reportId,
          metricId = projectMetricNullValueId,
          value = null,
      )

      insertProjectMetricTarget(
          projectMetricId = projectMetricNotPublishableId,
          year = 2030,
          target = 999,
      )
      insertReportProjectMetric(
          reportId = reportId,
          metricId = projectMetricNotPublishableId,
          value = 999,
      )

      // Seeds Collected is not publishable
      insertSystemMetricTarget(metric = SystemMetric.SeedsCollected, year = 2030, target = 999)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SeedsCollected,
          status = ReportMetricStatus.Achieved,
          systemValue = 999,
      )

      insertSystemMetricTarget(metric = SystemMetric.Seedlings, year = 2030, target = 50)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.Seedlings,
          status = ReportMetricStatus.OnTrack,
          overrideValue = 49,
          systemValue = 39,
          projectsComments = "Seedlings underperformance justification",
          progressNotes = "Seedlings progress notes",
      )

      insertSystemMetricTarget(metric = SystemMetric.SpeciesPlanted, year = 2030, target = 10)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SpeciesPlanted,
          status = ReportMetricStatus.Achieved,
          systemValue = 10,
      )

      // Metrics can be published even with no target set
      insertSystemMetricTarget(metric = SystemMetric.TreesPlanted, year = 2030, target = null)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.TreesPlanted,
          status = ReportMetricStatus.Achieved,
          systemValue = 100,
      )

      insertSystemMetricTarget(metric = SystemMetric.SurvivalRate, year = 2030, target = 0)
      insertReportSystemMetric(
          reportId = reportId,
          metric = SystemMetric.SurvivalRate,
          status = ReportMetricStatus.Unlikely,
          systemValue = 51,
      )
    }

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
          value = 100,
          progressNotes = "Existing progress notes",
          projectsComments = "Existing underperformance justification",
      )

      insertPublishedReportStandardMetric(
          metricId = standardMetricNullValueId,
          value = 100,
      )

      insertPublishedReportStandardMetric(
          metricId = standardMetricNotPublishableId,
          value = 100,
      )

      insertPublishedReportProjectMetric(
          metricId = projectMetricId1,
          value = 100,
          progressNotes = "Existing progress notes",
          projectsComments = "Existing underperformance justification",
      )

      insertPublishedReportProjectMetric(
          metricId = projectMetricId2,
          value = 100,
          projectsComments = "Existing underperformance justification",
      )

      insertPublishedReportProjectMetric(
          metricId = projectMetricNotPublishableId,
          value = 100,
      )

      insertPublishedReportSystemMetric(
          metric = SystemMetric.SeedsCollected,
          value = 100,
      )

      insertPublishedReportSystemMetric(
          metric = SystemMetric.Seedlings,
          value = 100,
          progressNotes = "Existing progress notes",
          projectsComments = "Existing underperformance justification",
      )

      insertPublishedReportSystemMetric(
          metric = SystemMetric.TreesPlanted,
          value = 100,
          projectsComments = "Existing underperformance justification",
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
                  value = 10,
                  projectsComments = null,
                  progressNotes = "Standard Metric 1 Progress notes",
              ),
              PublishedReportStandardMetricsRecord(
                  reportId = reportId,
                  standardMetricId = standardMetricId2,
                  statusId = ReportMetricStatus.OnTrack,
                  value = 19,
                  projectsComments = "Standard Metric 2 Underperformance",
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
                  value = 30,
                  projectsComments = null,
                  progressNotes = "Project Metric 1 Progress notes",
              ),
              PublishedReportProjectMetricsRecord(
                  reportId = reportId,
                  projectMetricId = projectMetricId2,
                  statusId = ReportMetricStatus.Unlikely,
                  value = 39,
                  projectsComments = "Project Metric 2 Underperformance",
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
                  value = 49,
                  projectsComments = "Seedlings underperformance justification",
                  progressNotes = "Seedlings progress notes",
              ),
              PublishedReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SpeciesPlanted,
                  statusId = ReportMetricStatus.Achieved,
                  value = 10,
              ),
              PublishedReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.TreesPlanted,
                  statusId = ReportMetricStatus.Achieved,
                  value = 100,
              ),
              PublishedReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.SurvivalRate,
                  statusId = ReportMetricStatus.Unlikely,
                  value = 51,
              ),
              PublishedReportSystemMetricsRecord(
                  reportId = reportId,
                  systemMetricId = SystemMetric.HectaresPlanted,
                  statusId = null,
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

      assertTableEquals(
          listOf(
              PublishedProjectMetricTargetsRecord(
                  projectId = projectId,
                  projectMetricId = projectMetricId1,
                  year = 2030,
                  target = 30,
              ),
              PublishedProjectMetricTargetsRecord(
                  projectId = projectId,
                  projectMetricId = projectMetricId2,
                  year = 2030,
                  target = 40,
              ),
              PublishedProjectMetricTargetsRecord(
                  projectId = projectId,
                  projectMetricId = projectMetricNullValueId,
                  year = 2030,
                  target = 999,
              ),
          ),
          "Published project metric targets table",
      )

      // Assert published metric targets for the report year (2030)
      assertTableEquals(
          listOf(
              PublishedStandardMetricTargetsRecord(
                  projectId = projectId,
                  standardMetricId = standardMetricId1,
                  year = 2030,
                  target = 10,
              ),
              PublishedStandardMetricTargetsRecord(
                  projectId = projectId,
                  standardMetricId = standardMetricId2,
                  year = 2030,
                  target = 20,
              ),
              PublishedStandardMetricTargetsRecord(
                  projectId = projectId,
                  standardMetricId = standardMetricNullValueId,
                  year = 2030,
                  target = 999,
              ),
          ),
          "Published standard metric targets table",
      )

      assertTableEquals(
          listOf(
              PublishedSystemMetricTargetsRecord(
                  projectId = projectId,
                  systemMetricId = SystemMetric.Seedlings,
                  year = 2030,
                  target = 50,
              ),
              PublishedSystemMetricTargetsRecord(
                  projectId = projectId,
                  systemMetricId = SystemMetric.SpeciesPlanted,
                  year = 2030,
                  target = 10,
              ),
              PublishedSystemMetricTargetsRecord(
                  projectId = projectId,
                  systemMetricId = SystemMetric.HectaresPlanted,
                  year = 2030,
                  target = null,
              ),
              PublishedSystemMetricTargetsRecord(
                  projectId = projectId,
                  systemMetricId = SystemMetric.TreesPlanted,
                  year = 2030,
                  target = null,
              ),
              PublishedSystemMetricTargetsRecord(
                  projectId = projectId,
                  systemMetricId = SystemMetric.SurvivalRate,
                  year = 2030,
                  target = 0,
              ),
          ),
          "Published system metric targets table",
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
            ),
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
            ),
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
            ),
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
            ),
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

    val plantingSiteId1 =
        insertPlantingSite(projectId = projectId, boundary = multiPolygon(1), insertHistory = false)
    val site1oldHistory = insertPlantingSiteHistory()
    val site1newHistory = insertPlantingSiteHistory()
    insertStratum(insertHistory = false)
    val site1zoneOldHistory = insertStratumHistory(plantingSiteHistoryId = site1oldHistory)
    insertStratumHistory()
    insertStratumT0TempDensity(
        speciesId = speciesId,
        stratumDensity = BigDecimal.valueOf(100).toPlantsPerHectare(),
    )
    insertStratumT0TempDensity(
        speciesId = otherSpeciesId,
        stratumDensity = BigDecimal.valueOf(110).toPlantsPerHectare(),
    )
    val subzoneId1 =
        insertSubstratum(
            areaHa = BigDecimal(10),
            plantingCompletedTime = plantingCompletedDate1.atStartOfDay().toInstant(ZoneOffset.UTC),
            insertHistory = false,
        )
    val subzone1oldHistory = insertSubstratumHistory(stratumHistoryId = site1zoneOldHistory)
    insertSubstratumHistory()
    val subzone1plot1 = insertMonitoringPlot(permanentIndex = 1, insertHistory = false)
    val subzone1plot1OldHist =
        insertMonitoringPlotHistory(
            substratumHistoryId = subzone1oldHistory,
            plantingSiteHistoryId = site1oldHistory,
        )
    val subzone1plot1Hist = insertMonitoringPlotHistory()
    insertPlotT0Density(
        speciesId = speciesId,
        plotDensity = BigDecimal.valueOf(10).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = otherSpeciesId,
        plotDensity = BigDecimal.valueOf(11).toPlantsPerHectare(),
    )
    val subzone1plot2 = insertMonitoringPlot(permanentIndex = null, insertHistory = false)
    val subzone1plot2OldHist =
        insertMonitoringPlotHistory(
            substratumHistoryId = subzone1oldHistory,
            plantingSiteHistoryId = site1oldHistory,
        )
    val subzone1plot2Hist = insertMonitoringPlotHistory()
    // these old densities from when the plot was permanent should be excluded
    insertPlotT0Density(
        speciesId = speciesId,
        plotDensity = BigDecimal.valueOf(12).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = otherSpeciesId,
        plotDensity = BigDecimal.valueOf(13).toPlantsPerHectare(),
    )

    val subzoneId2 =
        insertSubstratum(
            areaHa = BigDecimal(20),
            plantingCompletedTime =
                pastPlantingCompletedDate.atStartOfDay().toInstant(ZoneOffset.UTC),
            insertHistory = false,
        )
    val subzone2oldHistory = insertSubstratumHistory(stratumHistoryId = site1zoneOldHistory)
    insertSubstratumHistory()
    // this plot was in observation1 but was reassigned from observation2
    val reassignedPlot = insertMonitoringPlot(permanentIndex = 2)
    val reassignedPlotHist = inserted.monitoringPlotHistoryId
    insertPlotT0Density(
        speciesId = speciesId,
        plotDensity = BigDecimal.valueOf(102).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = otherSpeciesId,
        plotDensity = BigDecimal.valueOf(103).toPlantsPerHectare(),
    )
    // this plot was temporary and is now permanent
    val newlyPermanentPlot = insertMonitoringPlot(permanentIndex = 3, insertHistory = false)
    val newlyPermanentPlotOldHist =
        insertMonitoringPlotHistory(
            substratumHistoryId = subzone2oldHistory,
            plantingSiteHistoryId = site1oldHistory,
        )
    val newlyPermanentPlotHist = insertMonitoringPlotHistory()
    insertPlotT0Density(
        speciesId = speciesId,
        plotDensity = BigDecimal.valueOf(30).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = otherSpeciesId,
        plotDensity = BigDecimal.valueOf(31).toPlantsPerHectare(),
    )
    // this plot was permanent and is now temporary, but was reassigned in observation2
    val newlyTemporaryPlot = insertMonitoringPlot(permanentIndex = null, insertHistory = false)
    val newlyTemporaryPlotOldHist =
        insertMonitoringPlotHistory(
            substratumHistoryId = subzone2oldHistory,
            plantingSiteHistoryId = site1oldHistory,
        )
    insertMonitoringPlotHistory()
    insertPlotT0Density(
        speciesId = speciesId,
        plotDensity = BigDecimal.valueOf(105).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = otherSpeciesId,
        plotDensity = BigDecimal.valueOf(106).toPlantsPerHectare(),
    )
    // this plot used to be in a subzone but is no longer
    val plotNotInSubzone =
        insertMonitoringPlot(permanentIndex = 4, insertHistory = false, substratumId = null)
    val plotNotInSubzoneOldHist =
        insertMonitoringPlotHistory(
            substratumHistoryId = subzone2oldHistory,
            plantingSiteHistoryId = site1oldHistory,
        )
    insertMonitoringPlotHistory(substratumId = null, substratumHistoryId = null)
    insertPlotT0Density(
        speciesId = speciesId,
        plotDensity = BigDecimal.valueOf(300).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = otherSpeciesId,
        plotDensity = BigDecimal.valueOf(301).toPlantsPerHectare(),
    )
    // temp plot observed in older observation only
    val previouslyObservedTempPlot =
        insertMonitoringPlot(permanentIndex = null, insertHistory = false)
    val previouslyObservedTempPlotOldHist =
        insertMonitoringPlotHistory(
            substratumHistoryId = subzone2oldHistory,
            plantingSiteHistoryId = site1oldHistory,
        )
    insertMonitoringPlotHistory()
    // Not counted towards hectares planted, not completed
    val incompleteSubzone =
        insertSubstratum(
            areaHa = BigDecimal(1000),
            plantingCompletedTime = null,
        )
    // plot was in a different subzone previously
    val incompleteSubzonePlot1 = insertMonitoringPlot(permanentIndex = 5, insertHistory = false)
    val incompleteSubzonePlot1OldHist =
        insertMonitoringPlotHistory(
            substratumHistoryId = subzone2oldHistory,
            plantingSiteHistoryId = site1oldHistory,
        )
    val incompleteSubzonePlot1Hist = insertMonitoringPlotHistory()
    insertPlotT0Density(
        speciesId = speciesId,
        plotDensity = BigDecimal.valueOf(40).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = otherSpeciesId,
        plotDensity = BigDecimal.valueOf(41).toPlantsPerHectare(),
    )
    // Not counted towards hectares planted, after reporting period
    val futureSubzone =
        insertSubstratum(
            areaHa = BigDecimal(3000),
            plantingCompletedTime =
                futurePlantingCompletedDate.atStartOfDay().toInstant(ZoneOffset.UTC),
            insertHistory = false,
        )
    val futureSubzoneOldHist = insertSubstratumHistory(stratumHistoryId = site1zoneOldHistory)
    insertSubstratumHistory()
    val futureSubzonePlot1 = insertMonitoringPlot(permanentIndex = 6, insertHistory = false)
    val futureSubzonePlot1OldHist =
        insertMonitoringPlotHistory(
            substratumHistoryId = futureSubzoneOldHist,
            plantingSiteHistoryId = site1oldHistory,
        )
    val futureSubzonePlot1Hist = insertMonitoringPlotHistory()
    insertPlotT0Density(
        speciesId = speciesId,
        plotDensity = BigDecimal.valueOf(50).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = otherSpeciesId,
        plotDensity = BigDecimal.valueOf(51).toPlantsPerHectare(),
    )

    val plantingSiteId2 = insertPlantingSite(projectId = projectId, boundary = multiPolygon(1))
    val plantingSiteHistoryId2 = insertPlantingSiteHistory()
    insertStratum()
    insertStratumT0TempDensity(
        speciesId = speciesId,
        stratumDensity = BigDecimal.valueOf(200).toPlantsPerHectare(),
    )
    val site2Subzone1 =
        insertSubstratum(
            areaHa = BigDecimal(30),
            plantingCompletedTime = plantingCompletedDate2.atStartOfDay().toInstant(ZoneOffset.UTC),
        )
    val subzone3plot1 = insertMonitoringPlot(permanentIndex = 1)
    val subzone3plot1Hist = inserted.monitoringPlotHistoryId
    insertPlotT0Density(
        speciesId = speciesId,
        plotDensity = BigDecimal.valueOf(65).toPlantsPerHectare(),
    )

    val otherPlantingSiteId =
        insertPlantingSite(projectId = otherProjectId, boundary = multiPolygon(1))
    val otherPlantingSiteHistoryId = insertPlantingSiteHistory()
    insertStratum()
    insertStratumHistory()
    insertStratumT0TempDensity(
        speciesId = speciesId,
        stratumDensity = BigDecimal.valueOf(300).toPlantsPerHectare(),
    )
    insertStratumT0TempDensity(
        speciesId = otherSpeciesId,
        stratumDensity = BigDecimal.valueOf(310).toPlantsPerHectare(),
    )
    // Not counted towards hectares planted, outside of project site
    val otherSiteSubzoneId =
        insertSubstratum(
            areaHa = BigDecimal(5000),
            plantingCompletedTime = plantingCompletedDate2.atStartOfDay().toInstant(ZoneOffset.UTC),
        )
    val excludedPlot = insertMonitoringPlot(permanentIndex = 1)
    val excludedPlotHist = inserted.monitoringPlotHistoryId
    insertPlotT0Density(
        speciesId = speciesId,
        plotDensity = BigDecimal.valueOf(5).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = otherSpeciesId,
        plotDensity = BigDecimal.valueOf(6).toPlantsPerHectare(),
    )

    val allPlotsOldHist =
        mapOf(
            subzone1plot1 to subzone1plot1OldHist,
            subzone1plot2 to subzone1plot2OldHist,
            newlyPermanentPlot to newlyPermanentPlotOldHist,
            newlyTemporaryPlot to newlyTemporaryPlotOldHist,
            incompleteSubzonePlot1 to incompleteSubzonePlot1OldHist,
            futureSubzonePlot1 to futureSubzonePlot1OldHist,
            plotNotInSubzone to plotNotInSubzoneOldHist,
            previouslyObservedTempPlot to previouslyObservedTempPlotOldHist,
            reassignedPlot to reassignedPlotHist,
        )
    val allPlotsObs2 =
        mapOf(
            newlyPermanentPlot to newlyPermanentPlotHist,
            incompleteSubzonePlot1 to incompleteSubzonePlot1Hist,
            futureSubzonePlot1 to futureSubzonePlot1Hist,
        )
    val allPlots =
        mapOf(
            subzone1plot1 to subzone1plot1Hist,
            subzone1plot2 to subzone1plot2Hist,
            newlyPermanentPlot to newlyPermanentPlotHist,
            incompleteSubzonePlot1 to incompleteSubzonePlot1Hist,
            futureSubzonePlot1 to futureSubzonePlot1Hist,
            previouslyObservedTempPlot to previouslyObservedTempPlotOldHist,
            subzone3plot1 to subzone3plot1Hist,
            excludedPlot to excludedPlotHist,
        )
    val site2plots = mapOf(subzone3plot1 to subzone3plot1Hist)
    val allSubzonesInSite1 =
        listOf(
            subzoneId1,
            subzoneId2,
            incompleteSubzone,
            futureSubzone,
        )
    val site1subzonesObservation2 =
        listOf(
            subzoneId2,
            incompleteSubzone,
            futureSubzone,
        )

    val tempPlots = listOf(subzone1plot2, previouslyObservedTempPlot)

    val deliveryId =
        insertDelivery(
            plantingSiteId = plantingSiteId1,
            withdrawalId = outplantWithdrawalId1,
        )
    insertPlanting(
        plantingSiteId = plantingSiteId1,
        substratumId = subzoneId1,
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
        substratumId = subzoneId1,
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
        substratumId = subzoneId1,
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
        substratumId = otherSiteSubzoneId,
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
        substratumId = subzoneId1,
        deliveryId = futureDeliveryId,
        numPlants = 9,
    )

    // Not the latest observation, so the number does not count towards survival rates
    val observationDate = getRandomDate(reportStartDate, reportEndDate.minusDays(1))
    val observationTime = observationDate.atStartOfDay().toInstant(ZoneOffset.UTC)
    val site1OldObservationId =
        insertObservation(
            plantingSiteId = plantingSiteId1,
            plantingSiteHistoryId = site1oldHistory,
            state = ObservationState.Completed,
            completedTime = observationTime,
        )
    allPlotsOldHist.forEach { (plotId, histId) ->
      insertObservationPlot(
          monitoringPlotId = plotId,
          monitoringPlotHistoryId = histId,
          isPermanent = plotId != newlyPermanentPlot && (plotId !in tempPlots),
          completedTime = observationTime,
      )
    }
    allSubzonesInSite1.forEach { insertObservationRequestedSubstratum(substratumId = it) }
    insertObservedSubstratumSpeciesTotals(
        observationId = site1OldObservationId,
        substratumId = subzoneId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 6,
        totalLive = 6,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1OldObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 6,
        // total live at a site level can be lower than subzone level because of disjoint subzones
        // set this to 0 to ensure that tests use subzone level for temp plots in survival rates
        totalLive = 0,
    )
    insertObservedSubstratumSpeciesTotals(
        observationId = site1OldObservationId,
        substratumId = subzoneId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = otherSpeciesId,
        permanentLive = 11,
        totalLive = 11,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1OldObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = otherSpeciesId,
        permanentLive = 11,
        totalLive = 0,
    )
    insertObservedSubstratumSpeciesTotals(
        observationId = site1OldObservationId,
        substratumId = subzoneId1,
        certainty = RecordedSpeciesCertainty.Other,
        speciesName = "Other",
        permanentLive = 6,
        totalLive = 6,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1OldObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Other,
        speciesName = "Other",
        permanentLive = 6,
        totalLive = 0,
    )

    val site1observation2 =
        insertObservation(
            plantingSiteId = plantingSiteId1,
            plantingSiteHistoryId = site1newHistory,
            state = ObservationState.Completed,
            completedTime = observationDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC),
        )
    allPlotsObs2.forEach { (plotId, histId) ->
      insertObservationPlot(
          monitoringPlotId = plotId,
          monitoringPlotHistoryId = histId,
          isPermanent = plotId !in tempPlots,
          completedTime = observationTime,
      )
    }
    site1subzonesObservation2.forEach { insertObservationRequestedSubstratum(substratumId = it) }
    insertObservedSubstratumSpeciesTotals(
        observationId = site1observation2,
        substratumId = subzoneId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 6,
        totalLive = 6,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1observation2,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 6,
        totalLive = 0,
    )
    insertObservedSubstratumSpeciesTotals(
        observationId = site1observation2,
        substratumId = subzoneId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = otherSpeciesId,
        permanentLive = 11,
        totalLive = 11,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1observation2,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = otherSpeciesId,
        permanentLive = 11,
        totalLive = 0,
    )
    insertObservedSubstratumSpeciesTotals(
        observationId = site1observation2,
        substratumId = subzoneId1,
        certainty = RecordedSpeciesCertainty.Other,
        speciesName = "Other",
        permanentLive = 6,
        totalLive = 6,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1observation2,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Other,
        speciesName = "Other",
        permanentLive = 6,
        totalLive = 0,
    )
    // Unknown plants are not counted towards survival rates
    insertObservedSubstratumSpeciesTotals(
        observationId = site1observation2,
        substratumId = subzoneId1,
        certainty = RecordedSpeciesCertainty.Unknown,
        permanentLive = 0,
        totalLive = 0,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1observation2,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Unknown,
        permanentLive = 0,
        totalLive = 0,
    )

    // future observations are not counted towards survival rates
    val futureObservationDate = reportEndDate.plusDays(1)
    val site1FutureObservationId =
        insertObservation(
            plantingSiteId = plantingSiteId1,
            plantingSiteHistoryId = site1newHistory,
            state = ObservationState.Completed,
            completedTime = futureObservationDate.atStartOfDay().toInstant(ZoneOffset.UTC),
        )
    site1subzonesObservation2.forEach { insertObservationRequestedSubstratum(substratumId = it) }
    allPlots.forEach { (plotId, histId) ->
      insertObservationPlot(
          monitoringPlotId = plotId,
          monitoringPlotHistoryId = histId,
          isPermanent = plotId !in tempPlots,
          completedTime = observationTime,
      )
    }
    insertObservedSubstratumSpeciesTotals(
        observationId = site1FutureObservationId,
        substratumId = subzoneId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = otherSpeciesId,
        permanentLive = 12,
        totalLive = 12,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1FutureObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = otherSpeciesId,
        permanentLive = 12,
        totalLive = 0,
    )
    insertObservedSubstratumSpeciesTotals(
        observationId = site1FutureObservationId,
        substratumId = subzoneId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 7,
        totalLive = 7,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1FutureObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 7,
        totalLive = 0,
    )
    insertObservedSubstratumSpeciesTotals(
        observationId = site1FutureObservationId,
        substratumId = subzoneId1,
        certainty = RecordedSpeciesCertainty.Other,
        speciesName = "Other",
        permanentLive = 7,
        totalLive = 7,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1FutureObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Other,
        speciesName = "Other",
        permanentLive = 7,
        totalLive = 0,
    )
    // Unknown plants are not counted towards survival rate
    insertObservedSubstratumSpeciesTotals(
        observationId = site1FutureObservationId,
        substratumId = subzoneId1,
        certainty = RecordedSpeciesCertainty.Unknown,
        permanentLive = 1,
        totalLive = 1,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site1FutureObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Unknown,
        permanentLive = 1,
        totalLive = 10,
    )

    // Latest observation before the reporting period counts towards survival rate
    val site2ObservationTime = reportStartDate.minusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
    val site2ObservationId =
        insertObservation(
            plantingSiteId = plantingSiteId2,
            plantingSiteHistoryId = plantingSiteHistoryId2,
            state = ObservationState.Completed,
            completedTime = site2ObservationTime,
        )
    site2plots.forEach { (plotId, histId) ->
      insertObservationPlot(
          monitoringPlotId = plotId,
          monitoringPlotHistoryId = histId,
          isPermanent = plotId !in tempPlots,
          completedTime = site2ObservationTime,
      )
    }
    insertObservationRequestedSubstratum(substratumId = site2Subzone1)
    insertObservedSubstratumSpeciesTotals(
        observationId = site2ObservationId,
        substratumId = site2Subzone1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 7,
        totalLive = 7,
    )
    insertObservedSiteSpeciesTotals(
        observationId = site2ObservationId,
        plantingSiteId = plantingSiteId2,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 7,
        totalLive = 0,
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
    allPlots.forEach { (plotId, histId) ->
      insertObservationPlot(
          monitoringPlotId = plotId,
          monitoringPlotHistoryId = histId,
          isPermanent = plotId !in tempPlots,
          completedTime = observationTime,
      )
    }
    insertObservationRequestedSubstratum(substratumId = otherSiteSubzoneId)
    insertObservedSubstratumSpeciesTotals(
        observationId = otherSiteObservationId,
        substratumId = otherSiteSubzoneId,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 0,
        totalLive = 0,
    )
    insertObservedSiteSpeciesTotals(
        observationId = otherSiteObservationId,
        plantingSiteId = plantingSiteId1,
        certainty = RecordedSpeciesCertainty.Known,
        speciesId = speciesId,
        permanentLive = 0,
        totalLive = 0,
    )
    // Total plants: 50
    // Dead plants: 20
  }

  private fun insertDataForSurvivalRates(reportStartDate: LocalDate, reportEndDate: LocalDate) {
    val species1 = insertSpecies()
    val species2 = insertSpecies()

    val plantingSite1 = insertPlantingSite(projectId = projectId, boundary = multiPolygon(1))
    val plantingSite1Hist = inserted.plantingSiteHistoryId
    insertStratum()
    insertStratumT0TempDensity(
        speciesId = species1,
        stratumDensity = BigDecimal.valueOf(20).toPlantsPerHectare(),
    )
    insertStratumT0TempDensity(
        speciesId = species2,
        stratumDensity = BigDecimal.valueOf(21).toPlantsPerHectare(),
    )
    val site1substratum = insertSubstratum()
    val site1permPlot = insertMonitoringPlot(permanentIndex = 1)
    val site1permPlotHist = inserted.monitoringPlotHistoryId
    insertPlotT0Density(
        speciesId = species1,
        plotDensity = BigDecimal.valueOf(10).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = species2,
        plotDensity = BigDecimal.valueOf(11).toPlantsPerHectare(),
    )
    val site1tempPlot = insertMonitoringPlot(permanentIndex = null)
    val site1tempPlotHist = inserted.monitoringPlotHistoryId
    val site1Plots = mapOf(site1permPlot to site1permPlotHist, site1tempPlot to site1tempPlotHist)

    val plantingSite2 = insertPlantingSite(projectId = projectId, boundary = multiPolygon(1))
    val plantingSite2Hist = inserted.plantingSiteHistoryId
    insertStratum()
    insertStratumT0TempDensity(
        speciesId = species1,
        stratumDensity = BigDecimal.valueOf(22).toPlantsPerHectare(),
    )
    insertStratumT0TempDensity(
        speciesId = species2,
        stratumDensity = BigDecimal.valueOf(23).toPlantsPerHectare(),
    )
    val site2substratum = insertSubstratum()
    val site2permPlot = insertMonitoringPlot(permanentIndex = 1)
    val site2permPlotHist = inserted.monitoringPlotHistoryId
    insertPlotT0Density(
        speciesId = species1,
        plotDensity = BigDecimal.valueOf(12).toPlantsPerHectare(),
    )
    insertPlotT0Density(
        speciesId = species2,
        plotDensity = BigDecimal.valueOf(13).toPlantsPerHectare(),
    )
    val site2TempPlot = insertMonitoringPlot(permanentIndex = null)
    val site2TempPlotHist = inserted.monitoringPlotHistoryId
    val site2Plots = mapOf(site2permPlot to site2permPlotHist, site2TempPlot to site2TempPlotHist)

    // this site doesn't have a survival rate so its values are excluded from the project-level
    // calculations
    val plantingSite3 = insertPlantingSite(projectId = projectId, boundary = multiPolygon(1))
    val plantingSite3Hist = inserted.plantingSiteHistoryId
    insertStratum()
    val site3substratum1 = insertSubstratum()
    val site3perm1 = insertMonitoringPlot(permanentIndex = 1) // missing plot t0
    val site3perm1Hist = inserted.monitoringPlotHistoryId
    val site3temp1 = insertMonitoringPlot(permanentIndex = null) // missing zone t0
    val site3temp1Hist = inserted.monitoringPlotHistoryId
    insertStratum()
    insertStratumT0TempDensity(stratumDensity = BigDecimal.valueOf(100).toPlantsPerHectare())
    val site3substratum2 = insertSubstratum()
    val site3perm2 = insertMonitoringPlot(permanentIndex = 2)
    val site3perm2Hist = inserted.monitoringPlotHistoryId
    insertPlotT0Density(plotDensity = BigDecimal.valueOf(200).toPlantsPerHectare())
    val site3temp2 = insertMonitoringPlot(permanentIndex = null)
    val site3temp2Hist = inserted.monitoringPlotHistoryId
    val site3Plots =
        mapOf(
            site3perm1 to site3perm1Hist,
            site3temp1 to site3temp1Hist,
            site3perm2 to site3perm2Hist,
            site3temp2 to site3temp2Hist,
        )

    val observationDate = getRandomDate(reportStartDate, reportEndDate.minusDays(1))
    val observationTime = observationDate.atStartOfDay().toInstant(ZoneOffset.UTC)
    insertObservation(
        plantingSiteId = plantingSite1,
        plantingSiteHistoryId = plantingSite1Hist,
        state = ObservationState.Completed,
        completedTime = observationTime,
    )
    insertObservationRequestedSubstratum(substratumId = site1substratum)
    site1Plots.forEach { (plotId, histId) ->
      insertObservationPlot(
          monitoringPlotId = plotId,
          monitoringPlotHistoryId = histId,
          isPermanent = plotId !in listOf(site1tempPlot),
          completedTime = observationTime,
      )
    }
    insertObservedSubstratumSpeciesTotals(
        speciesId = species1,
        substratumId = site1substratum,
        totalLive = 11,
    )
    insertObservedSubstratumSpeciesTotals(
        speciesId = species2,
        substratumId = site1substratum,
        totalLive = 12,
    )
    insertObservedSiteSpeciesTotals(
        speciesId = species1,
        plantingSiteId = plantingSite1,
        permanentLive = 5,
    )
    insertObservedSiteSpeciesTotals(
        speciesId = species2,
        plantingSiteId = plantingSite1,
        permanentLive = 6,
    )

    // future observation should be excluded
    val futureTime = reportEndDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
    insertObservation(
        plantingSiteId = plantingSite1,
        plantingSiteHistoryId = plantingSite1Hist,
        state = ObservationState.Completed,
        completedTime = futureTime,
    )
    insertObservationRequestedSubstratum(substratumId = site1substratum)
    site1Plots.forEach { (plotId, histId) ->
      insertObservationPlot(
          monitoringPlotId = plotId,
          monitoringPlotHistoryId = histId,
          isPermanent = plotId !in listOf(site1tempPlot),
          completedTime = futureTime,
      )
    }

    insertObservation(
        plantingSiteId = plantingSite2,
        plantingSiteHistoryId = plantingSite2Hist,
        state = ObservationState.Completed,
        completedTime = observationTime,
    )
    insertObservationRequestedSubstratum(substratumId = site2substratum)
    site2Plots.forEach { (plotId, histId) ->
      insertObservationPlot(
          monitoringPlotId = plotId,
          monitoringPlotHistoryId = histId,
          isPermanent = plotId != site2TempPlot,
          completedTime = observationTime,
      )
    }
    insertObservedSubstratumSpeciesTotals(
        speciesId = species1,
        substratumId = site2substratum,
        totalLive = 13,
    )
    insertObservedSubstratumSpeciesTotals(
        speciesId = species2,
        substratumId = site2substratum,
        totalLive = 14,
    )
    insertObservedSiteSpeciesTotals(
        speciesId = species1,
        plantingSiteId = plantingSite2,
        permanentLive = 7,
    )
    insertObservedSiteSpeciesTotals(
        speciesId = species2,
        plantingSiteId = plantingSite2,
        permanentLive = 8,
    )

    insertObservation(
        plantingSiteId = plantingSite3,
        plantingSiteHistoryId = plantingSite3Hist,
        state = ObservationState.Completed,
        completedTime = observationTime.plusSeconds(86400),
    )
    insertObservationRequestedSubstratum(substratumId = site3substratum1)
    insertObservationRequestedSubstratum(substratumId = site3substratum2)
    site3Plots.forEach { (plotId, histId) ->
      insertObservationPlot(
          monitoringPlotId = plotId,
          monitoringPlotHistoryId = histId,
          isPermanent = plotId !in listOf(site3temp1, site3temp2),
          completedTime = observationTime.plusSeconds(86400),
      )
    }
    insertObservedSubstratumSpeciesTotals(
        speciesId = species1,
        substratumId = site3substratum1,
        totalLive = 20,
    )
    insertObservedSubstratumSpeciesTotals(
        speciesId = species2,
        substratumId = site3substratum1,
        totalLive = 21,
    )
    insertObservedSubstratumSpeciesTotals(
        speciesId = species1,
        substratumId = site3substratum2,
        totalLive = 30,
    )
    insertObservedSubstratumSpeciesTotals(
        speciesId = species2,
        substratumId = site3substratum2,
        totalLive = 31,
    )
    insertObservedSiteSpeciesTotals(
        speciesId = species1,
        plantingSiteId = plantingSite3,
        permanentLive = 50,
    )
    insertObservedSiteSpeciesTotals(
        speciesId = species2,
        plantingSiteId = plantingSite3,
        permanentLive = 51,
    )
  }

  private fun insertSystemMetricTargetsForReport(reportId: ReportId) {
    SystemMetric.entries.forEach { metric ->
      insertReportSystemMetric(
          reportId = reportId,
          metric = metric,
      )
    }
  }

  @Nested
  inner class UpdateProjectMetricTarget {
    @Test
    fun `inserts new target`() {
      val projectMetricId =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Test metric",
              name = "Test Metric",
              reference = "1.0",
              type = MetricType.Activity,
          )

      store.updateProjectMetricTarget(
          projectId = projectId,
          year = 2024,
          metricId = projectMetricId,
          target = 100,
      )

      val targets = reportProjectMetricTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertEquals(projectId, targets[0].projectId)
      assertEquals(projectMetricId, targets[0].projectMetricId)
      assertEquals(2024, targets[0].year)
      assertEquals(100, targets[0].target)
    }

    @Test
    fun `updates existing target`() {
      val projectMetricId =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Test metric",
              name = "Test Metric",
              reference = "1.0",
              type = MetricType.Activity,
          )

      insertProjectMetricTarget(
          projectId = projectId,
          projectMetricId = projectMetricId,
          year = 2024,
          target = 100,
      )

      store.updateProjectMetricTarget(
          projectId = projectId,
          year = 2024,
          metricId = projectMetricId,
          target = 150,
      )

      val targets = reportProjectMetricTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertEquals(150, targets[0].target)
    }

    @Test
    fun `allows null target`() {
      val projectMetricId =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Test metric",
              name = "Test Metric",
              reference = "1.0",
              type = MetricType.Activity,
          )

      store.updateProjectMetricTarget(
          projectId = projectId,
          year = 2024,
          metricId = projectMetricId,
          target = null,
      )

      val targets = reportProjectMetricTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertNull(targets[0].target)
    }

    @Test
    fun `requires permission to update project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      val projectMetricId =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Test metric",
              name = "Test Metric",
              reference = "1.0",
              type = MetricType.Activity,
          )

      assertThrows<AccessDeniedException> {
        store.updateProjectMetricTarget(
            projectId = projectId,
            year = 2024,
            metricId = projectMetricId,
            target = 100,
        )
      }
    }
  }

  @Nested
  inner class UpdateStandardMetricTarget {
    @Test
    fun `inserts new target`() {
      val standardMetricId =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Test metric",
              name = "Test Metric",
              reference = "1.0",
              type = MetricType.Activity,
          )

      store.updateStandardMetricTarget(
          projectId = projectId,
          year = 2024,
          metricId = standardMetricId,
          target = 200,
      )

      val targets = reportStandardMetricTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertEquals(projectId, targets[0].projectId)
      assertEquals(standardMetricId, targets[0].standardMetricId)
      assertEquals(2024, targets[0].year)
      assertEquals(200, targets[0].target)
    }

    @Test
    fun `updates existing target`() {
      val standardMetricId =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Test metric",
              name = "Test Metric",
              reference = "1.0",
              type = MetricType.Activity,
          )

      insertStandardMetricTarget(
          projectId = projectId,
          standardMetricId = standardMetricId,
          year = 2024,
          target = 200,
      )

      store.updateStandardMetricTarget(
          projectId = projectId,
          year = 2024,
          metricId = standardMetricId,
          target = 250,
      )

      val targets = reportStandardMetricTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertEquals(250, targets[0].target)
    }

    @Test
    fun `allows null target`() {
      val standardMetricId =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Test metric",
              name = "Test Metric",
              reference = "1.0",
              type = MetricType.Activity,
          )

      store.updateStandardMetricTarget(
          projectId = projectId,
          year = 2024,
          metricId = standardMetricId,
          target = null,
      )

      val targets = reportStandardMetricTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertNull(targets[0].target)
    }

    @Test
    fun `requires permission to update project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      val standardMetricId =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Test metric",
              name = "Test Metric",
              reference = "1.0",
              type = MetricType.Activity,
          )

      assertThrows<AccessDeniedException> {
        store.updateStandardMetricTarget(
            projectId = projectId,
            year = 2024,
            metricId = standardMetricId,
            target = 200,
        )
      }
    }
  }

  @Nested
  inner class UpdateSystemMetricTarget {
    @Test
    fun `inserts new target`() {
      store.updateSystemMetricTarget(
          projectId = projectId,
          year = 2024,
          metricId = SystemMetric.TreesPlanted,
          target = 500,
      )

      val targets = reportSystemMetricTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertEquals(projectId, targets[0].projectId)
      assertEquals(SystemMetric.TreesPlanted, targets[0].systemMetricId)
      assertEquals(2024, targets[0].year)
      assertEquals(500, targets[0].target)
    }

    @Test
    fun `updates existing target`() {
      insertSystemMetricTarget(
          projectId = projectId,
          metric = SystemMetric.TreesPlanted,
          year = 2024,
          target = 500,
      )

      store.updateSystemMetricTarget(
          projectId = projectId,
          year = 2024,
          metricId = SystemMetric.TreesPlanted,
          target = 600,
      )

      val targets = reportSystemMetricTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertEquals(600, targets[0].target)
    }

    @Test
    fun `allows null target`() {
      store.updateSystemMetricTarget(
          projectId = projectId,
          year = 2024,
          metricId = SystemMetric.TreesPlanted,
          target = null,
      )

      val targets = reportSystemMetricTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertNull(targets[0].target)
    }

    @Test
    fun `requires permission to update project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.updateSystemMetricTarget(
            projectId = projectId,
            year = 2024,
            metricId = SystemMetric.TreesPlanted,
            target = 500,
        )
      }
    }
  }

  @Nested
  inner class FetchReportProjectMetricTargets {
    @Test
    fun `requires permission to read project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> { store.fetchReportProjectMetricTargets(projectId) }
    }

    @Test
    fun `returns all project metric targets for a project`() {
      val projectMetricId1 =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Test metric 1",
              name = "Test Metric 1",
              reference = "1.0",
              type = MetricType.Activity,
          )
      val projectMetricId2 =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Test metric 2",
              name = "Test Metric 2",
              reference = "2.0",
              type = MetricType.Activity,
          )

      insertProjectMetricTarget(
          projectId = projectId,
          projectMetricId = projectMetricId1,
          year = 2024,
          target = 100,
      )
      insertProjectMetricTarget(
          projectId = projectId,
          projectMetricId = projectMetricId2,
          year = 2024,
          target = 200,
      )
      insertProjectMetricTarget(
          projectId = projectId,
          projectMetricId = projectMetricId1,
          year = 2025,
          target = 150,
      )

      val targets = store.fetchReportProjectMetricTargets(projectId)

      assertEquals(
          listOf(
              ReportProjectMetricTargetModel(projectMetricId1, 100, 2024),
              ReportProjectMetricTargetModel(projectMetricId2, 200, 2024),
              ReportProjectMetricTargetModel(projectMetricId1, 150, 2025),
          ),
          targets,
      )
    }
  }

  @Nested
  inner class FetchReportStandardMetricTargets {
    @Test
    fun `requires permission to read project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> { store.fetchReportStandardMetricTargets(projectId) }
    }

    @Test
    fun `returns all standard metric targets for a project`() {
      val standardMetricId1 =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Test metric 1",
              name = "Test Metric 1",
              reference = "1.0",
              type = MetricType.Activity,
          )
      val standardMetricId2 =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Test metric 2",
              name = "Test Metric 2",
              reference = "2.0",
              type = MetricType.Activity,
          )

      insertStandardMetricTarget(
          projectId = projectId,
          standardMetricId = standardMetricId1,
          year = 2024,
          target = 300,
      )
      insertStandardMetricTarget(
          projectId = projectId,
          standardMetricId = standardMetricId2,
          year = 2024,
          target = 400,
      )
      insertStandardMetricTarget(
          projectId = projectId,
          standardMetricId = standardMetricId1,
          year = 2025,
          target = 350,
      )

      val targets = store.fetchReportStandardMetricTargets(projectId)

      assertEquals(
          listOf(
              ReportStandardMetricTargetModel(standardMetricId1, 300, 2024),
              ReportStandardMetricTargetModel(standardMetricId2, 400, 2024),
              ReportStandardMetricTargetModel(standardMetricId1, 350, 2025),
          ),
          targets,
      )
    }
  }

  @Nested
  inner class FetchReportSystemMetricTargets {
    @Test
    fun `requires permission to read project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> { store.fetchReportSystemMetricTargets(projectId) }
    }

    @Test
    fun `returns all system metric targets for a project`() {
      insertSystemMetricTarget(
          projectId = projectId,
          metric = SystemMetric.TreesPlanted,
          year = 2024,
          target = 500,
      )
      insertSystemMetricTarget(
          projectId = projectId,
          metric = SystemMetric.SeedsCollected,
          year = 2024,
          target = 1000,
      )
      insertSystemMetricTarget(
          projectId = projectId,
          metric = SystemMetric.TreesPlanted,
          year = 2025,
          target = 600,
      )

      val targets = store.fetchReportSystemMetricTargets(projectId)

      assertEquals(
          listOf(
              ReportSystemMetricTargetModel(SystemMetric.SeedsCollected, 1000, 2024),
              ReportSystemMetricTargetModel(SystemMetric.TreesPlanted, 500, 2024),
              ReportSystemMetricTargetModel(SystemMetric.TreesPlanted, 600, 2025),
          ),
          targets,
      )
    }
  }
}
