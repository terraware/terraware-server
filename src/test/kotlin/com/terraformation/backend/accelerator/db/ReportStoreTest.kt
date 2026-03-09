package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.event.AcceleratorReportPublishedEvent
import com.terraformation.backend.accelerator.event.AcceleratorReportSubmittedEvent
import com.terraformation.backend.accelerator.event.AcceleratorReportUpcomingEvent
import com.terraformation.backend.accelerator.model.AutoCalculatedIndicatorTargetsModel
import com.terraformation.backend.accelerator.model.CommonIndicatorModel
import com.terraformation.backend.accelerator.model.CommonIndicatorTargetsModel
import com.terraformation.backend.accelerator.model.CumulativeIndicatorProgressModel
import com.terraformation.backend.accelerator.model.ExistingProjectReportConfigModel
import com.terraformation.backend.accelerator.model.NewProjectReportConfigModel
import com.terraformation.backend.accelerator.model.ProjectIndicatorModel
import com.terraformation.backend.accelerator.model.ProjectIndicatorTargetsModel
import com.terraformation.backend.accelerator.model.ReportAutoCalculatedIndicatorEntryModel
import com.terraformation.backend.accelerator.model.ReportAutoCalculatedIndicatorModel
import com.terraformation.backend.accelerator.model.ReportAutoCalculatedIndicatorTargetModel
import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.accelerator.model.ReportCommonIndicatorModel
import com.terraformation.backend.accelerator.model.ReportCommonIndicatorTargetModel
import com.terraformation.backend.accelerator.model.ReportIndicatorEntryModel
import com.terraformation.backend.accelerator.model.ReportModel
import com.terraformation.backend.accelerator.model.ReportPhotoModel
import com.terraformation.backend.accelerator.model.ReportProjectIndicatorModel
import com.terraformation.backend.accelerator.model.ReportProjectIndicatorTargetModel
import com.terraformation.backend.accelerator.model.YearlyIndicatorTargetModel
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.SimpleUserModel
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ReportNotFoundException
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorFrequency
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ReportId
import com.terraformation.backend.db.accelerator.ReportIndicatorStatus
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.ReportStatus
import com.terraformation.backend.db.accelerator.tables.records.AutoCalculatedIndicatorTargetsRecord
import com.terraformation.backend.db.accelerator.tables.records.CommonIndicatorTargetsRecord
import com.terraformation.backend.db.accelerator.tables.records.ProjectAcceleratorDetailsRecord
import com.terraformation.backend.db.accelerator.tables.records.ProjectIndicatorTargetsRecord
import com.terraformation.backend.db.accelerator.tables.records.ProjectReportConfigsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportAchievementsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportAutoCalculatedIndicatorsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportChallengesRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportCommonIndicatorsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportProjectIndicatorsRecord
import com.terraformation.backend.db.accelerator.tables.records.ReportsRecord
import com.terraformation.backend.db.accelerator.tables.references.AUTO_CALCULATED_INDICATORS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_ACHIEVEMENTS
import com.terraformation.backend.db.accelerator.tables.references.REPORT_CHALLENGES
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.Role
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.funder.tables.records.PublishedAutoCalculatedIndicatorBaselinesRecord
import com.terraformation.backend.db.funder.tables.records.PublishedAutoCalculatedIndicatorTargetsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedCommonIndicatorBaselinesRecord
import com.terraformation.backend.db.funder.tables.records.PublishedCommonIndicatorTargetsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedProjectIndicatorBaselinesRecord
import com.terraformation.backend.db.funder.tables.records.PublishedProjectIndicatorTargetsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportAchievementsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportAutoCalculatedIndicatorsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportChallengesRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportCommonIndicatorsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportPhotosRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportProjectIndicatorsRecord
import com.terraformation.backend.db.funder.tables.records.PublishedReportsRecord
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
  private lateinit var otherProjectId: ProjectId
  private lateinit var projectId: ProjectId

  private val sitesLiveSum = 6 + 11 + 6 + 7
  private val site1T0Density = 10 + 11 + 30 + 31 + 40 + 41 + 50 + 51
  private val site1T0DensityWithTemp = 10 + 11 + 100 + 110 + 30 + 31 + 40 + 41 + 50 + 51
  private val site2T0Density = 65

  @BeforeEach
  fun setup() {
    organizationId = insertOrganization(timeZone = ZoneOffset.UTC)
    otherProjectId = insertProject()
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
    fun `includes indicators`() {
      val configId = insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted)

      val projectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              classId = IndicatorClass.Cumulative,
              description = "Project Indicator description",
              frequency = IndicatorFrequency.Annual,
              name = "Project Indicator Name",
              notes = "Project indicator notes",
              primaryDataSource = "Project data source",
              refId = "2.0",
              level = IndicatorLevel.Process,
          )

      // Insert target into new target table (report end date is 1970-01-02, so year is 1970)
      insertReportProjectIndicatorTarget(
          projectIndicatorId = projectIndicatorId,
          year = 1970,
          target = 100,
      )
      insertReportProjectIndicator(
          reportId = reportId,
          indicatorId = projectIndicatorId,
          status = ReportIndicatorStatus.OnTrack,
          modifiedTime = Instant.ofEpochSecond(1500),
          modifiedBy = user.userId,
      )
      insertProjectIndicatorBaselineTarget(baseline = 1, endTarget = 1000)

      val projectIndicators =
          listOf(
              ReportProjectIndicatorModel(
                  indicator =
                      ProjectIndicatorModel(
                          category = IndicatorCategory.ProjectObjectives,
                          classId = IndicatorClass.Cumulative,
                          description = "Project Indicator description",
                          frequency = IndicatorFrequency.Annual,
                          id = projectIndicatorId,
                          isPublishable = true,
                          name = "Project Indicator Name",
                          notes = "Project indicator notes",
                          primaryDataSource = "Project data source",
                          level = IndicatorLevel.Process,
                          projectId = projectId,
                          refId = "2.0",
                          tfOwner = "Carbon",
                      ),
                  entry =
                      ReportIndicatorEntryModel(
                          target = 100,
                          status = ReportIndicatorStatus.OnTrack,
                          modifiedTime = Instant.ofEpochSecond(1500),
                          modifiedBy = user.userId,
                      ),
                  baseline = BigDecimal.ONE,
                  endOfProjectTarget = BigDecimal("1000"),
                  currentYearProgress = emptyList(),
              ),
          )

      val commonIndicatorId1 =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              classId = IndicatorClass.Level,
              description = "Climate common indicator description",
              frequency = IndicatorFrequency.BiAnnual,
              level = IndicatorLevel.Process,
              name = "Climate Common Indicator",
              notes = "Common indicator notes",
              primaryDataSource = "Common data source",
              refId = "2.1",
          )

      val commonIndicatorId2 =
          insertCommonIndicator(
              category = IndicatorCategory.Community,
              description = "Community indicator description",
              level = IndicatorLevel.Outcome,
              name = "Community Indicator",
              refId = "10.0",
          )

      val commonIndicatorId3 =
          insertCommonIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Project objectives indicator description",
              level = IndicatorLevel.Goal,
              name = "Project Objectives Indicator",
              refId = "2.0",
          )

      // Insert targets into new target tables
      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId1,
          year = 1970,
          target = 55,
      )
      insertReportCommonIndicator(
          reportId = reportId,
          indicatorId = commonIndicatorId1,
          value = 45,
          projectsComments = "Almost at target",
          progressNotes = "Not quite there yet",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )
      insertCommonIndicatorBaselineTarget(
          commonIndicatorId = commonIndicatorId1,
          baseline = BigDecimal.TWO,
      )

      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId2,
          year = 1970,
          target = 25,
      )
      insertReportCommonIndicator(
          reportId = reportId,
          indicatorId = commonIndicatorId2,
          status = ReportIndicatorStatus.Unlikely,
          modifiedTime = Instant.ofEpochSecond(1500),
          modifiedBy = user.userId,
      )
      insertCommonIndicatorBaselineTarget(
          commonIndicatorId = commonIndicatorId2,
          endTarget = BigDecimal("2000"),
      )

      val commonIndicators =
          listOf(
              // ordered by reference
              ReportCommonIndicatorModel(
                  indicator =
                      CommonIndicatorModel(
                          category = IndicatorCategory.ProjectObjectives,
                          description = "Project objectives indicator description",
                          id = commonIndicatorId3,
                          isPublishable = true,
                          level = IndicatorLevel.Goal,
                          name = "Project Objectives Indicator",
                          refId = "2.0",
                          tfOwner = "Carbon",
                      ),
                  // all fields are null because no target/value have been set yet
                  entry = ReportIndicatorEntryModel(),
                  baseline = null,
                  endOfProjectTarget = null,
              ),
              ReportCommonIndicatorModel(
                  indicator =
                      CommonIndicatorModel(
                          category = IndicatorCategory.Climate,
                          classId = IndicatorClass.Level,
                          description = "Climate common indicator description",
                          frequency = IndicatorFrequency.BiAnnual,
                          id = commonIndicatorId1,
                          isPublishable = true,
                          level = IndicatorLevel.Process,
                          name = "Climate Common Indicator",
                          notes = "Common indicator notes",
                          primaryDataSource = "Common data source",
                          refId = "2.1",
                          tfOwner = "Carbon",
                      ),
                  entry =
                      ReportIndicatorEntryModel(
                          target = 55,
                          value = 45,
                          projectsComments = "Almost at target",
                          progressNotes = "Not quite there yet",
                          modifiedTime = Instant.ofEpochSecond(3000),
                          modifiedBy = user.userId,
                      ),
                  baseline = BigDecimal.TWO,
              ),
              ReportCommonIndicatorModel(
                  indicator =
                      CommonIndicatorModel(
                          category = IndicatorCategory.Community,
                          description = "Community indicator description",
                          id = commonIndicatorId2,
                          isPublishable = true,
                          level = IndicatorLevel.Outcome,
                          name = "Community Indicator",
                          refId = "10.0",
                          tfOwner = "Carbon",
                      ),
                  entry =
                      ReportIndicatorEntryModel(
                          target = 25,
                          status = ReportIndicatorStatus.Unlikely,
                          modifiedTime = Instant.ofEpochSecond(1500),
                          modifiedBy = user.userId,
                      ),
                  endOfProjectTarget = BigDecimal("2000"),
              ),
          )

      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.Seedlings,
          year = 1970,
          target = 1000,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.Seedlings,
          modifiedTime = Instant.ofEpochSecond(2500),
          modifiedBy = user.userId,
      )

      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          year = 1970,
          target = 2000,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 1800,
          systemTime = Instant.ofEpochSecond(8000),
          modifiedTime = Instant.ofEpochSecond(500),
          modifiedBy = user.userId,
      )
      insertAutoCalculatedIndicatorBaselineTarget(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          baseline = BigDecimal.TEN,
      )

      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.TreesPlanted,
          year = 1970,
          target = 600,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.TreesPlanted,
          systemValue = 300,
          systemTime = Instant.ofEpochSecond(7000),
          overrideValue = 800,
          status = ReportIndicatorStatus.Achieved,
          modifiedTime = Instant.ofEpochSecond(700),
          modifiedBy = user.userId,
      )
      insertAutoCalculatedIndicatorBaselineTarget(
          indicator = AutoCalculatedIndicator.TreesPlanted,
          baseline = BigDecimal("20"),
          endTarget = BigDecimal("1500"),
      )

      // These are ordered by reference.
      val autoCalculatedIndicators =
          listOf(
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.SeedsCollected,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          target = 2000,
                          systemValue = 1800,
                          systemTime = Instant.ofEpochSecond(8000),
                          modifiedTime = Instant.ofEpochSecond(500),
                          modifiedBy = user.userId,
                      ),
                  baseline = BigDecimal.TEN,
                  currentYearProgress =
                      listOf(CumulativeIndicatorProgressModel(ReportQuarter.Q1, 1800)),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.HectaresPlanted,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          systemValue = 0,
                      ),
                  currentYearProgress = emptyList(),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.Seedlings,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          target = 1000,
                          systemValue = 0,
                          modifiedTime = Instant.ofEpochSecond(2500),
                          modifiedBy = user.userId,
                      ),
                  currentYearProgress = emptyList(),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.TreesPlanted,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          target = 600,
                          systemValue = 300,
                          systemTime = Instant.ofEpochSecond(7000),
                          overrideValue = 800,
                          status = ReportIndicatorStatus.Achieved,
                          modifiedTime = Instant.ofEpochSecond(700),
                          modifiedBy = user.userId,
                      ),
                  baseline = BigDecimal("20"),
                  endOfProjectTarget = BigDecimal("1500"),
                  currentYearProgress =
                      listOf(CumulativeIndicatorProgressModel(ReportQuarter.Q1, 800)),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.SpeciesPlanted,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          systemValue = 0,
                      ),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.SurvivalRate,
                  entry = ReportAutoCalculatedIndicatorEntryModel(systemValue = null),
              ),
          )

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              projectDealName = "DEAL_Report Project",
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
              projectIndicators = projectIndicators,
              commonIndicators = commonIndicators,
              autoCalculatedIndicators = autoCalculatedIndicators,
          )

      assertEquals(listOf(reportModel), store.fetch(includeIndicators = true))
    }

    @Test
    fun `queries Terraware data for auto calculated indicator`() {
      insertProjectReportConfig()
      insertReport(
          status = ReportStatus.NotSubmitted,
          quarter = ReportQuarter.Q1,
          startDate = LocalDate.of(2025, Month.JANUARY, 1),
          endDate = LocalDate.of(2025, Month.MARCH, 31),
      )

      insertDataForAutoCalculatedIndicators(
          reportStartDate = LocalDate.of(2025, Month.JANUARY, 1),
          reportEndDate = LocalDate.of(2025, Month.MARCH, 31),
      )

      assertEquals(
          listOf(
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.SeedsCollected,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          systemValue = 98,
                      ),
                  currentYearProgress = emptyList(),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.HectaresPlanted,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          systemValue = 60,
                      ),
                  currentYearProgress = emptyList(),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.Seedlings,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          systemValue = 83,
                      ),
                  currentYearProgress = emptyList(),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.TreesPlanted,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          systemValue = 27,
                      ),
                  currentYearProgress = emptyList(),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.SpeciesPlanted,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          systemValue = 1,
                      ),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.SurvivalRate,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          systemValue =
                              (sitesLiveSum * 100.0 / (site1T0Density + site2T0Density))
                                  .roundToInt(),
                      ),
              ),
          ),
          store
              .fetch(includeFuture = true, includeIndicators = true)
              .first()
              .autoCalculatedIndicators,
          "All indicators",
      )

      // check just survival rate with temp plots
      with(PLANTING_SITES) {
        dslContext.update(this).set(SURVIVAL_RATE_INCLUDES_TEMP_PLOTS, true).execute()
      }

      val autoCalculatedIndicators =
          store
              .fetch(includeFuture = true, includeIndicators = true)
              .first()
              .autoCalculatedIndicators

      assertEquals(
          ReportAutoCalculatedIndicatorModel(
              indicator = AutoCalculatedIndicator.SurvivalRate,
              entry =
                  ReportAutoCalculatedIndicatorEntryModel(
                      systemValue =
                          (sitesLiveSum * 100.0 / (site1T0DensityWithTemp + site2T0Density))
                              .roundToInt(),
                  ),
          ),
          autoCalculatedIndicators.find { it.indicator == AutoCalculatedIndicator.SurvivalRate },
          "Should include temp plots in survival rate indicator",
      )
    }

    @Test
    fun `project survival rate excludes planting sites that don't have survival rates`() {
      val startDate = LocalDate.of(2025, Month.APRIL, 1)
      val endDate = LocalDate.of(2025, Month.JUNE, 30)
      insertProjectReportConfig()
      insertReport(
          status = ReportStatus.NotSubmitted,
          quarter = ReportQuarter.Q2,
          startDate = startDate,
          endDate = endDate,
      )

      insertDataForSurvivalRates(startDate, endDate)

      val autoCalculatedIndicators =
          store
              .fetch(includeFuture = true, includeIndicators = true)
              .first()
              .autoCalculatedIndicators

      assertEquals(
          ReportAutoCalculatedIndicatorModel(
              indicator = AutoCalculatedIndicator.SurvivalRate,
              entry =
                  ReportAutoCalculatedIndicatorEntryModel(
                      systemValue = ((5 + 6 + 7 + 8) * 100.0 / (10 + 11 + 12 + 13)).roundToInt(),
                  ),
          ),
          autoCalculatedIndicators.find { it.indicator == AutoCalculatedIndicator.SurvivalRate },
          "Project survival rate correctly excludes sites without a survival rate",
      )

      // check survival rate with temp plots
      with(PLANTING_SITES) {
        dslContext.update(this).set(SURVIVAL_RATE_INCLUDES_TEMP_PLOTS, true).execute()
      }
      val autoCalculatedIndicatorsWithTemp =
          store
              .fetch(includeFuture = true, includeIndicators = true)
              .first()
              .autoCalculatedIndicators

      assertEquals(
          ReportAutoCalculatedIndicatorModel(
              indicator = AutoCalculatedIndicator.SurvivalRate,
              entry =
                  ReportAutoCalculatedIndicatorEntryModel(
                      systemValue =
                          ((11 + 12 + 13 + 14) * 100.0 / (10 + 11 + 12 + 13 + 20 + 21 + 22 + 23))
                              .roundToInt(),
                  ),
          ),
          autoCalculatedIndicatorsWithTemp.find {
            it.indicator == AutoCalculatedIndicator.SurvivalRate
          },
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

    @Test
    fun `only includes project indicator targets for the report year and project`() {
      clock.instant = LocalDate.of(2025, Month.JANUARY, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
      insertProjectReportConfig()
      insertReport(
          endDate = LocalDate.of(2024, Month.DECEMBER, 31),
      )
      val projectIndicatorId = insertProjectIndicator()

      // Target for the report's year (2024) - should be included
      insertReportProjectIndicatorTarget(
          projectIndicatorId = projectIndicatorId,
          year = 2024,
          target = 100,
      )
      // Target for a different year - should not cause duplicate rows
      insertReportProjectIndicatorTarget(
          projectIndicatorId = projectIndicatorId,
          year = 2025,
          target = 999,
      )
      // Target for a different project in the same year - should not cause duplicate rows
      insertReportProjectIndicatorTarget(
          projectId = otherProjectId,
          projectIndicatorId = projectIndicatorId,
          year = 2024,
          target = 888,
      )

      val report = store.fetch(includeIndicators = true).single()
      val expected =
          ReportProjectIndicatorModel(
              indicator =
                  ProjectIndicatorModel(
                      id = projectIndicatorId,
                      active = true,
                      category = IndicatorCategory.ProjectObjectives,
                      description = null,
                      isPublishable = true,
                      level = IndicatorLevel.Goal,
                      name = "Indicator name",
                      projectId = projectId,
                      refId = "1.1",
                      tfOwner = "Carbon",
                  ),
              entry = ReportIndicatorEntryModel(target = 100),
          )
      assertEquals(
          listOf(expected),
          report.projectIndicators,
          "Other years and projects don't affect project indicators",
      )
    }

    @Test
    fun `only includes common indicator targets for the report year and project`() {
      clock.instant = LocalDate.of(2025, Month.JANUARY, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
      insertProjectReportConfig()
      insertReport(
          endDate = LocalDate.of(2024, Month.DECEMBER, 31),
      )
      val commonIndicatorId = insertCommonIndicator()

      // Target for the report's year (2024) - should be included
      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId,
          year = 2024,
          target = 200,
      )
      // Target for a different year - should not cause duplicate rows
      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId,
          year = 2025,
          target = 999,
      )
      // Target for a different project in the same year - should not cause duplicate rows
      insertReportCommonIndicatorTarget(
          projectId = otherProjectId,
          commonIndicatorId = commonIndicatorId,
          year = 2024,
          target = 888,
      )

      val report = store.fetch(includeIndicators = true).single()
      val expected =
          ReportCommonIndicatorModel(
              indicator =
                  CommonIndicatorModel(
                      id = commonIndicatorId,
                      category = IndicatorCategory.ProjectObjectives,
                      description = null,
                      isPublishable = true,
                      level = IndicatorLevel.Goal,
                      name = "Indicator name",
                      refId = "1.1",
                      tfOwner = "Carbon",
                  ),
              entry = ReportIndicatorEntryModel(target = 200),
          )
      assertEquals(
          listOf(expected),
          report.commonIndicators,
          "Other years and projects don't affect common indicators",
      )
    }

    @Test
    fun `only includes auto-calculated indicator targets for the report year and project`() {
      clock.instant = LocalDate.of(2025, Month.JANUARY, 1).atStartOfDay().toInstant(ZoneOffset.UTC)
      insertProjectReportConfig()
      insertReport(
          endDate = LocalDate.of(2024, Month.DECEMBER, 31),
      )

      // Target for the report's year (2024) - should be included
      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          year = 2024,
          target = 300,
      )
      // Target for a different year - should not cause duplicate rows
      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          year = 2025,
          target = 999,
      )
      // Target for a different project in the same year - should not cause duplicate rows
      insertReportAutoCalculatedIndicatorTarget(
          projectId = otherProjectId,
          indicator = AutoCalculatedIndicator.SeedsCollected,
          year = 2024,
          target = 888,
      )

      val report = store.fetch(includeIndicators = true).single()
      val expected =
          ReportAutoCalculatedIndicatorModel(
              indicator = AutoCalculatedIndicator.SeedsCollected,
              entry =
                  ReportAutoCalculatedIndicatorEntryModel(
                      target = 300,
                      systemValue = 0,
                  ),
              currentYearProgress = emptyList(),
          )
      assertEquals(
          expected,
          report.autoCalculatedIndicators.single {
            it.indicator == AutoCalculatedIndicator.SeedsCollected
          },
          "Other years and projects don't affect auto-calculated indicators",
      )
    }

    @Test
    fun `omits inactive indicators from results`() {
      insertProjectReportConfig()
      insertReport(status = ReportStatus.NotSubmitted)

      val activeCommonId = insertCommonIndicator(name = "Active Common Indicator")
      insertCommonIndicator(name = "Inactive Common Indicator", active = false)

      val activeProjectId = insertProjectIndicator(name = "Active Project Indicator")
      insertProjectIndicator(name = "Inactive Project Indicator", active = false)

      // Mark SeedsCollected inactive via direct DB update
      dslContext
          .update(AUTO_CALCULATED_INDICATORS)
          .set(AUTO_CALCULATED_INDICATORS.ACTIVE, false)
          .where(AUTO_CALCULATED_INDICATORS.ID.eq(AutoCalculatedIndicator.SeedsCollected))
          .execute()

      val report = store.fetch(includeIndicators = true).single()

      assertEquals(
          listOf(activeCommonId),
          report.commonIndicators.map { it.indicator.id },
          "Only the active common indicator should be returned",
      )
      assertEquals(
          listOf(activeProjectId),
          report.projectIndicators.map { it.indicator.id },
          "Only the active project indicator should be returned",
      )
      assertFalse(
          report.autoCalculatedIndicators.any {
            it.indicator == AutoCalculatedIndicator.SeedsCollected
          },
          "Inactive auto-calculated indicator SeedsCollected should not appear in results",
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
    fun `cumulative indicators contain previous year total`() {
      val otherProjectConfigId = insertProjectReportConfig(projectId = otherProjectId)
      val configId = insertProjectReportConfig()

      // multiple indicators should not interfere with each other's totals
      val projectIndicatorId1 =
          insertProjectIndicator(
              classId = IndicatorClass.Cumulative,
              name = "Project Indicator Name 1",
          )
      val projectIndicatorId2 =
          insertProjectIndicator(
              classId = IndicatorClass.Cumulative,
              name = "Project Indicator Name 2",
          )
      val otherProjectIndicatorId =
          insertProjectIndicator(
              projectId = otherProjectId,
              classId = IndicatorClass.Cumulative,
              name = "Other Project's Indicator",
          )
      val commonIndicatorId1 =
          insertCommonIndicator(
              name = "Common Indicator Name 1",
              classId = IndicatorClass.Cumulative,
          )
      val commonIndicatorId2 =
          insertCommonIndicator(
              name = "Common Indicator Name 2",
              classId = IndicatorClass.Cumulative,
          )

      // other project's indicators shouldn't affect total
      insertReport(
          projectId = otherProjectId,
          endDate = LocalDate.of(2024, 6, 30),
          configId = otherProjectConfigId,
      )
      insertReportProjectIndicator(indicatorId = otherProjectIndicatorId, value = 10_000)
      insertReportCommonIndicator(indicatorId = commonIndicatorId1, value = 20_000)
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 30_000,
      )

      // oldReport1
      insertReport(endDate = LocalDate.of(2024, 9, 30))
      insertReportProjectIndicator(indicatorId = projectIndicatorId1, value = 10)
      insertReportProjectIndicator(indicatorId = projectIndicatorId2, value = 11)
      insertReportCommonIndicator(indicatorId = commonIndicatorId1, value = 20)
      insertReportCommonIndicator(indicatorId = commonIndicatorId2, value = 21)
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 30,
      )
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.HectaresPlanted,
          systemValue = 29,
          overrideValue = 31, // overrideValue takes precedence
      )

      // oldReport2
      insertReport(endDate = LocalDate.of(2024, 12, 31))
      insertReportProjectIndicator(indicatorId = projectIndicatorId1, value = 100)
      insertReportProjectIndicator(indicatorId = projectIndicatorId2, value = 101)
      insertReportCommonIndicator(indicatorId = commonIndicatorId1, value = 200)
      insertReportCommonIndicator(indicatorId = commonIndicatorId2, value = 201)

      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 300,
          overrideValue = 301, // overrideValue takes precedence
      )
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.HectaresPlanted,
          systemValue = 299,
          overrideValue = 302, // overrideValue takes precedence
      )

      val reportId =
          insertReport(startDate = LocalDate.of(2025, 1, 1), endDate = LocalDate.of(2025, 3, 31))
      insertReportProjectIndicator(indicatorId = projectIndicatorId1)
      insertReportProjectIndicator(indicatorId = projectIndicatorId2)
      insertReportCommonIndicator(indicatorId = commonIndicatorId1)
      insertReportCommonIndicator(indicatorId = commonIndicatorId2)
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 3000,
      )
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.HectaresPlanted,
          systemValue = 4000,
      )

      val projectIndicators =
          listOf(
              ReportProjectIndicatorModel(
                  indicator =
                      ProjectIndicatorModel(
                          category = IndicatorCategory.ProjectObjectives,
                          classId = IndicatorClass.Cumulative,
                          description = null,
                          id = projectIndicatorId1,
                          isPublishable = true,
                          level = IndicatorLevel.Goal,
                          name = "Project Indicator Name 1",
                          projectId = projectId,
                          refId = "1.1",
                          tfOwner = "Carbon",
                      ),
                  entry =
                      ReportIndicatorEntryModel(
                          modifiedBy = user.userId,
                          modifiedTime = Instant.EPOCH,
                      ),
                  currentYearProgress = emptyList(),
                  previousYearCumulativeTotal = BigDecimal("110"),
              ),
              ReportProjectIndicatorModel(
                  indicator =
                      ProjectIndicatorModel(
                          category = IndicatorCategory.ProjectObjectives,
                          classId = IndicatorClass.Cumulative,
                          description = null,
                          id = projectIndicatorId2,
                          isPublishable = true,
                          level = IndicatorLevel.Goal,
                          name = "Project Indicator Name 2",
                          projectId = projectId,
                          refId = "1.1",
                          tfOwner = "Carbon",
                      ),
                  entry =
                      ReportIndicatorEntryModel(
                          modifiedBy = user.userId,
                          modifiedTime = Instant.EPOCH,
                      ),
                  currentYearProgress = emptyList(),
                  previousYearCumulativeTotal = BigDecimal("112"),
              ),
          )
      val commonIndicators =
          listOf(
              ReportCommonIndicatorModel(
                  indicator =
                      CommonIndicatorModel(
                          category = IndicatorCategory.ProjectObjectives,
                          classId = IndicatorClass.Cumulative,
                          description = null,
                          id = commonIndicatorId1,
                          isPublishable = true,
                          level = IndicatorLevel.Goal,
                          name = "Common Indicator Name 1",
                          refId = "1.1",
                          tfOwner = "Carbon",
                      ),
                  entry =
                      ReportIndicatorEntryModel(
                          modifiedBy = user.userId,
                          modifiedTime = Instant.EPOCH,
                      ),
                  currentYearProgress = emptyList(),
                  previousYearCumulativeTotal = BigDecimal("220"),
              ),
              ReportCommonIndicatorModel(
                  indicator =
                      CommonIndicatorModel(
                          category = IndicatorCategory.ProjectObjectives,
                          classId = IndicatorClass.Cumulative,
                          description = null,
                          id = commonIndicatorId2,
                          isPublishable = true,
                          level = IndicatorLevel.Goal,
                          name = "Common Indicator Name 2",
                          refId = "1.1",
                          tfOwner = "Carbon",
                      ),
                  entry =
                      ReportIndicatorEntryModel(
                          modifiedBy = user.userId,
                          modifiedTime = Instant.EPOCH,
                      ),
                  currentYearProgress = emptyList(),
                  previousYearCumulativeTotal = BigDecimal("222"),
              ),
          )
      val autoCalculatedIndicators =
          listOf(
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.SeedsCollected,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          modifiedBy = user.userId,
                          modifiedTime = Instant.EPOCH,
                          systemValue = 3000,
                          systemTime = Instant.EPOCH,
                      ),
                  currentYearProgress =
                      listOf(CumulativeIndicatorProgressModel(ReportQuarter.Q1, 3000)),
                  previousYearCumulativeTotal = BigDecimal("331"),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.HectaresPlanted,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          modifiedBy = user.userId,
                          modifiedTime = Instant.EPOCH,
                          systemValue = 4000,
                          systemTime = Instant.EPOCH,
                      ),
                  currentYearProgress =
                      listOf(CumulativeIndicatorProgressModel(ReportQuarter.Q1, 4000)),
                  previousYearCumulativeTotal = BigDecimal("333"),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.Seedlings,
                  entry = ReportAutoCalculatedIndicatorEntryModel(systemValue = 0),
                  currentYearProgress = emptyList(),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.TreesPlanted,
                  entry = ReportAutoCalculatedIndicatorEntryModel(systemValue = 0),
                  currentYearProgress = emptyList(),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.SpeciesPlanted,
                  entry = ReportAutoCalculatedIndicatorEntryModel(systemValue = 0),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.SurvivalRate,
                  entry = ReportAutoCalculatedIndicatorEntryModel(systemValue = null),
              ),
          )

      val reportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              projectDealName = "DEAL_Report Project",
              quarter = ReportQuarter.Q1,
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.of(2025, 1, 1),
              endDate = LocalDate.of(2025, 3, 31),
              createdBy = user.userId,
              createdByUser = SimpleUserModel(user.userId, "First Last"),
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedByUser = SimpleUserModel(user.userId, "First Last"),
              modifiedTime = Instant.EPOCH,
              projectIndicators = projectIndicators,
              commonIndicators = commonIndicators,
              autoCalculatedIndicators = autoCalculatedIndicators,
          )

      clock.instant = LocalDate.of(2025, 3, 20).atStartOfDay().toInstant(ZoneOffset.UTC)
      assertEquals(
          reportModel,
          store.fetchOne(reportId = reportId, includeIndicators = true),
          "Indicators should have previous year cumulative totals",
      )
    }

    @Test
    fun `cumulative indicators contain current year progress`() {
      val otherProjectConfigId = insertProjectReportConfig(projectId = otherProjectId)
      val configId = insertProjectReportConfig()

      val cumulativeCommonIndicatorId =
          insertCommonIndicator(
              name = "Cumulative Common Indicator",
              classId = IndicatorClass.Cumulative,
          )
      val levelCommonIndicatorId =
          insertCommonIndicator(name = "Level Common Indicator", classId = IndicatorClass.Level)
      val cumulativeProjectIndicatorId =
          insertProjectIndicator(
              name = "Cumulative Project Indicator",
              classId = IndicatorClass.Cumulative,
          )
      val levelProjectIndicatorId =
          insertProjectIndicator(name = "Level Project Indicator", classId = IndicatorClass.Level)

      // Q1 report - values contribute to Q3's currentYearProgress
      val q1ReportId =
          insertReport(
              quarter = ReportQuarter.Q1,
              startDate = LocalDate.of(2025, 1, 1),
              endDate = LocalDate.of(2025, 3, 31),
          )
      insertReportCommonIndicator(
          reportId = q1ReportId,
          indicatorId = cumulativeCommonIndicatorId,
          value = 11,
      )
      insertReportCommonIndicator(
          reportId = q1ReportId,
          indicatorId = levelCommonIndicatorId,
          value = 100,
      )
      insertReportProjectIndicator(
          reportId = q1ReportId,
          indicatorId = cumulativeProjectIndicatorId,
          value = 21,
      )
      insertReportProjectIndicator(
          reportId = q1ReportId,
          indicatorId = levelProjectIndicatorId,
          value = 100,
      )
      insertReportAutoCalculatedIndicator(
          reportId = q1ReportId,
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 31,
      )
      insertReportAutoCalculatedIndicator(
          reportId = q1ReportId,
          indicator = AutoCalculatedIndicator.HectaresPlanted,
          systemValue = 41,
          overrideValue = 40, // override takes precedence
      )

      // Q2 report - values contribute to Q3's currentYearProgress
      val q2ReportId =
          insertReport(
              quarter = ReportQuarter.Q2,
              startDate = LocalDate.of(2025, 4, 1),
              endDate = LocalDate.of(2025, 6, 30),
          )
      insertReportCommonIndicator(
          reportId = q2ReportId,
          indicatorId = cumulativeCommonIndicatorId,
          value = 12,
      )
      insertReportCommonIndicator(
          reportId = q2ReportId,
          indicatorId = levelCommonIndicatorId,
          value = 200,
      )
      insertReportProjectIndicator(
          reportId = q2ReportId,
          indicatorId = cumulativeProjectIndicatorId,
          value = 22,
      )
      insertReportProjectIndicator(
          reportId = q2ReportId,
          indicatorId = levelProjectIndicatorId,
          value = 200,
      )
      insertReportAutoCalculatedIndicator(
          reportId = q2ReportId,
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 32,
      )
      insertReportAutoCalculatedIndicator(
          reportId = q2ReportId,
          indicator = AutoCalculatedIndicator.HectaresPlanted,
          systemValue = 42,
      )

      // Q3 report (current report being fetched)
      val reportId =
          insertReport(
              quarter = ReportQuarter.Q3,
              startDate = LocalDate.of(2025, 7, 1),
              endDate = LocalDate.of(2025, 9, 30),
          )
      insertReportCommonIndicator(indicatorId = cumulativeCommonIndicatorId, value = 13)
      insertReportCommonIndicator(indicatorId = levelCommonIndicatorId, value = 300)
      insertReportProjectIndicator(indicatorId = cumulativeProjectIndicatorId, value = 23)
      insertReportProjectIndicator(indicatorId = levelProjectIndicatorId, value = 300)
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 33,
      )
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.HectaresPlanted,
          systemValue = 43,
      )

      // Q4 report (future quarter relative to Q3 - should be excluded from currentYearProgress)
      val q4ReportId =
          insertReport(
              quarter = ReportQuarter.Q4,
              startDate = LocalDate.of(2025, 10, 1),
              endDate = LocalDate.of(2025, 12, 31),
          )
      insertReportCommonIndicator(
          reportId = q4ReportId,
          indicatorId = cumulativeCommonIndicatorId,
          value = 14,
      )
      insertReportProjectIndicator(
          reportId = q4ReportId,
          indicatorId = cumulativeProjectIndicatorId,
          value = 24,
      )
      insertReportAutoCalculatedIndicator(
          reportId = q4ReportId,
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 34,
      )
      insertReportAutoCalculatedIndicator(
          reportId = q4ReportId,
          indicator = AutoCalculatedIndicator.HectaresPlanted,
          systemValue = 44,
      )

      // Other project's report in same year - should not appear in current year progress
      insertReport(
          projectId = otherProjectId,
          configId = otherProjectConfigId,
          quarter = ReportQuarter.Q1,
          endDate = LocalDate.of(2025, 3, 31),
      )
      insertReportCommonIndicator(indicatorId = cumulativeCommonIndicatorId, value = 888)

      clock.instant = LocalDate.of(2025, 12, 20).atStartOfDay().toInstant(ZoneOffset.UTC)

      val expectedReportModel =
          ReportModel(
              id = reportId,
              configId = configId,
              projectId = projectId,
              projectDealName = "DEAL_Report Project",
              quarter = ReportQuarter.Q3,
              status = ReportStatus.NotSubmitted,
              startDate = LocalDate.of(2025, 7, 1),
              endDate = LocalDate.of(2025, 9, 30),
              createdBy = user.userId,
              createdByUser = SimpleUserModel(user.userId, "First Last"),
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedByUser = SimpleUserModel(user.userId, "First Last"),
              modifiedTime = Instant.EPOCH,
              commonIndicators =
                  listOf(
                      ReportCommonIndicatorModel(
                          indicator =
                              CommonIndicatorModel(
                                  category = IndicatorCategory.ProjectObjectives,
                                  classId = IndicatorClass.Cumulative,
                                  description = null,
                                  id = cumulativeCommonIndicatorId,
                                  isPublishable = true,
                                  level = IndicatorLevel.Goal,
                                  name = "Cumulative Common Indicator",
                                  refId = "1.1",
                                  tfOwner = "Carbon",
                              ),
                          entry =
                              ReportIndicatorEntryModel(
                                  modifiedBy = user.userId,
                                  modifiedTime = Instant.EPOCH,
                                  value = 13,
                              ),
                          currentYearProgress =
                              listOf(
                                  CumulativeIndicatorProgressModel(ReportQuarter.Q1, 11),
                                  CumulativeIndicatorProgressModel(ReportQuarter.Q2, 12),
                                  CumulativeIndicatorProgressModel(ReportQuarter.Q3, 13),
                              ),
                      ),
                      ReportCommonIndicatorModel(
                          indicator =
                              CommonIndicatorModel(
                                  category = IndicatorCategory.ProjectObjectives,
                                  classId = IndicatorClass.Level,
                                  description = null,
                                  id = levelCommonIndicatorId,
                                  isPublishable = true,
                                  level = IndicatorLevel.Goal,
                                  name = "Level Common Indicator",
                                  refId = "1.1",
                                  tfOwner = "Carbon",
                              ),
                          entry =
                              ReportIndicatorEntryModel(
                                  modifiedBy = user.userId,
                                  modifiedTime = Instant.EPOCH,
                                  value = 300,
                              ),
                      ),
                  ),
              projectIndicators =
                  listOf(
                      ReportProjectIndicatorModel(
                          indicator =
                              ProjectIndicatorModel(
                                  category = IndicatorCategory.ProjectObjectives,
                                  classId = IndicatorClass.Cumulative,
                                  description = null,
                                  id = cumulativeProjectIndicatorId,
                                  isPublishable = true,
                                  level = IndicatorLevel.Goal,
                                  name = "Cumulative Project Indicator",
                                  projectId = projectId,
                                  refId = "1.1",
                                  tfOwner = "Carbon",
                              ),
                          entry =
                              ReportIndicatorEntryModel(
                                  modifiedBy = user.userId,
                                  modifiedTime = Instant.EPOCH,
                                  value = 23,
                              ),
                          currentYearProgress =
                              listOf(
                                  CumulativeIndicatorProgressModel(ReportQuarter.Q1, 21),
                                  CumulativeIndicatorProgressModel(ReportQuarter.Q2, 22),
                                  CumulativeIndicatorProgressModel(ReportQuarter.Q3, 23),
                              ),
                      ),
                      ReportProjectIndicatorModel(
                          indicator =
                              ProjectIndicatorModel(
                                  category = IndicatorCategory.ProjectObjectives,
                                  classId = IndicatorClass.Level,
                                  description = null,
                                  id = levelProjectIndicatorId,
                                  isPublishable = true,
                                  level = IndicatorLevel.Goal,
                                  name = "Level Project Indicator",
                                  projectId = projectId,
                                  refId = "1.1",
                                  tfOwner = "Carbon",
                              ),
                          entry =
                              ReportIndicatorEntryModel(
                                  modifiedBy = user.userId,
                                  modifiedTime = Instant.EPOCH,
                                  value = 300,
                              ),
                      ),
                  ),
              autoCalculatedIndicators =
                  listOf(
                      ReportAutoCalculatedIndicatorModel(
                          indicator = AutoCalculatedIndicator.SeedsCollected,
                          entry =
                              ReportAutoCalculatedIndicatorEntryModel(
                                  modifiedBy = user.userId,
                                  modifiedTime = Instant.EPOCH,
                                  systemValue = 33,
                                  systemTime = Instant.EPOCH,
                              ),
                          currentYearProgress =
                              listOf(
                                  CumulativeIndicatorProgressModel(ReportQuarter.Q1, 31),
                                  CumulativeIndicatorProgressModel(ReportQuarter.Q2, 32),
                                  CumulativeIndicatorProgressModel(ReportQuarter.Q3, 33),
                              ),
                      ),
                      ReportAutoCalculatedIndicatorModel(
                          indicator = AutoCalculatedIndicator.HectaresPlanted,
                          entry =
                              ReportAutoCalculatedIndicatorEntryModel(
                                  modifiedBy = user.userId,
                                  modifiedTime = Instant.EPOCH,
                                  systemValue = 43,
                                  systemTime = Instant.EPOCH,
                              ),
                          currentYearProgress =
                              listOf(
                                  CumulativeIndicatorProgressModel(ReportQuarter.Q1, 40),
                                  CumulativeIndicatorProgressModel(ReportQuarter.Q2, 42),
                                  CumulativeIndicatorProgressModel(ReportQuarter.Q3, 43),
                              ),
                      ),
                      ReportAutoCalculatedIndicatorModel(
                          indicator = AutoCalculatedIndicator.Seedlings,
                          entry = ReportAutoCalculatedIndicatorEntryModel(systemValue = 0),
                          currentYearProgress = emptyList(),
                      ),
                      ReportAutoCalculatedIndicatorModel(
                          indicator = AutoCalculatedIndicator.TreesPlanted,
                          entry = ReportAutoCalculatedIndicatorEntryModel(systemValue = 0),
                          currentYearProgress = emptyList(),
                      ),
                      ReportAutoCalculatedIndicatorModel(
                          indicator = AutoCalculatedIndicator.SpeciesPlanted,
                          entry = ReportAutoCalculatedIndicatorEntryModel(systemValue = 0),
                      ),
                      ReportAutoCalculatedIndicatorModel(
                          indicator = AutoCalculatedIndicator.SurvivalRate,
                          entry = ReportAutoCalculatedIndicatorEntryModel(systemValue = null),
                      ),
                  ),
          )

      assertEquals(
          expectedReportModel,
          store.fetchOne(reportId = reportId, includeIndicators = true),
          "Cumulative indicators should have current year progress from all same-year quarters with values excluding future quarters",
      )
    }

    @Test
    fun `returns report, with indicators optionally`() {
      val configId = insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted)

      val projectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              classId = IndicatorClass.Level,
              description = "Project Indicator description",
              frequency = IndicatorFrequency.MRVCycle,
              name = "Project Indicator Name",
              notes = "Project indicator notes",
              primaryDataSource = "Project data source",
              refId = "2.0",
              level = IndicatorLevel.Process,
          )

      insertReportProjectIndicatorTarget(
          projectIndicatorId = projectIndicatorId,
          year = 1970,
          target = 100,
      )
      insertReportProjectIndicator(
          reportId = reportId,
          indicatorId = projectIndicatorId,
          status = ReportIndicatorStatus.OnTrack,
          modifiedTime = Instant.ofEpochSecond(1500),
          modifiedBy = user.userId,
      )

      val projectIndicators =
          listOf(
              ReportProjectIndicatorModel(
                  indicator =
                      ProjectIndicatorModel(
                          category = IndicatorCategory.ProjectObjectives,
                          classId = IndicatorClass.Level,
                          description = "Project Indicator description",
                          frequency = IndicatorFrequency.MRVCycle,
                          id = projectIndicatorId,
                          isPublishable = true,
                          level = IndicatorLevel.Process,
                          name = "Project Indicator Name",
                          notes = "Project indicator notes",
                          primaryDataSource = "Project data source",
                          projectId = projectId,
                          refId = "2.0",
                          tfOwner = "Carbon",
                      ),
                  entry =
                      ReportIndicatorEntryModel(
                          target = 100,
                          status = ReportIndicatorStatus.OnTrack,
                          modifiedTime = Instant.ofEpochSecond(1500),
                          modifiedBy = user.userId,
                      ),
              ),
          )

      val commonIndicatorId1 =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              classId = IndicatorClass.Cumulative,
              description = "Climate common indicator description",
              frequency = IndicatorFrequency.Annual,
              level = IndicatorLevel.Process,
              name = "Climate Common Indicator",
              notes = "Common indicator notes",
              primaryDataSource = "Common data source",
              refId = "2.1",
          )

      val commonIndicatorId2 =
          insertCommonIndicator(
              category = IndicatorCategory.Community,
              description = "Community indicator description",
              isPublishable = false,
              level = IndicatorLevel.Outcome,
              name = "Community Indicator",
              refId = "10.0",
          )

      val commonIndicatorId3 =
          insertCommonIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Project objectives indicator description",
              level = IndicatorLevel.Goal,
              name = "Project Objectives Indicator",
              refId = "2.0",
          )

      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId1,
          year = 1970,
          target = 55,
      )
      insertReportCommonIndicator(
          reportId = reportId,
          indicatorId = commonIndicatorId1,
          value = 45,
          projectsComments = "Almost at target",
          progressNotes = "Not quite there yet",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId2,
          year = 1970,
          target = 25,
      )
      insertReportCommonIndicator(
          reportId = reportId,
          indicatorId = commonIndicatorId2,
          status = ReportIndicatorStatus.OffTrack,
          modifiedTime = Instant.ofEpochSecond(1500),
          modifiedBy = user.userId,
      )

      val commonIndicators =
          listOf(
              // ordered by reference
              ReportCommonIndicatorModel(
                  indicator =
                      CommonIndicatorModel(
                          category = IndicatorCategory.ProjectObjectives,
                          description = "Project objectives indicator description",
                          id = commonIndicatorId3,
                          isPublishable = true,
                          level = IndicatorLevel.Goal,
                          name = "Project Objectives Indicator",
                          refId = "2.0",
                          tfOwner = "Carbon",
                      ),
                  // all fields are null because no target/value have been set yet
                  entry = ReportIndicatorEntryModel(),
              ),
              ReportCommonIndicatorModel(
                  indicator =
                      CommonIndicatorModel(
                          category = IndicatorCategory.Climate,
                          classId = IndicatorClass.Cumulative,
                          description = "Climate common indicator description",
                          frequency = IndicatorFrequency.Annual,
                          id = commonIndicatorId1,
                          isPublishable = true,
                          level = IndicatorLevel.Process,
                          name = "Climate Common Indicator",
                          notes = "Common indicator notes",
                          primaryDataSource = "Common data source",
                          refId = "2.1",
                          tfOwner = "Carbon",
                      ),
                  entry =
                      ReportIndicatorEntryModel(
                          target = 55,
                          value = 45,
                          projectsComments = "Almost at target",
                          progressNotes = "Not quite there yet",
                          modifiedTime = Instant.ofEpochSecond(3000),
                          modifiedBy = user.userId,
                      ),
                  currentYearProgress =
                      listOf(CumulativeIndicatorProgressModel(ReportQuarter.Q1, 45)),
              ),
              ReportCommonIndicatorModel(
                  indicator =
                      CommonIndicatorModel(
                          category = IndicatorCategory.Community,
                          description = "Community indicator description",
                          id = commonIndicatorId2,
                          isPublishable = false,
                          level = IndicatorLevel.Outcome,
                          name = "Community Indicator",
                          refId = "10.0",
                          tfOwner = "Carbon",
                      ),
                  entry =
                      ReportIndicatorEntryModel(
                          target = 25,
                          status = ReportIndicatorStatus.OffTrack,
                          modifiedTime = Instant.ofEpochSecond(1500),
                          modifiedBy = user.userId,
                      ),
              ),
          )

      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.Seedlings,
          year = 1970,
          target = 1000,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.Seedlings,
          modifiedTime = Instant.ofEpochSecond(2500),
          modifiedBy = user.userId,
      )

      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          year = 1970,
          target = 2000,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 1800,
          systemTime = Instant.ofEpochSecond(8000),
          modifiedTime = Instant.ofEpochSecond(500),
          modifiedBy = user.userId,
      )

      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.TreesPlanted,
          year = 1970,
          target = 600,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.TreesPlanted,
          systemValue = 300,
          systemTime = Instant.ofEpochSecond(7000),
          overrideValue = 800,
          status = ReportIndicatorStatus.Achieved,
          modifiedTime = Instant.ofEpochSecond(700),
          modifiedBy = user.userId,
      )

      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.SurvivalRate,
          systemValue = null,
          systemTime = Instant.ofEpochSecond(9000),
          modifiedTime = Instant.ofEpochSecond(700),
          modifiedBy = user.userId,
      )

      // These are ordered by reference.
      val autoCalculatedIndicators =
          listOf(
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.SeedsCollected,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          target = 2000,
                          systemValue = 1800,
                          systemTime = Instant.ofEpochSecond(8000),
                          modifiedTime = Instant.ofEpochSecond(500),
                          modifiedBy = user.userId,
                      ),
                  currentYearProgress =
                      listOf(CumulativeIndicatorProgressModel(ReportQuarter.Q1, 1800)),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.HectaresPlanted,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          systemValue = 0,
                      ),
                  currentYearProgress = emptyList(),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.Seedlings,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          target = 1000,
                          systemValue = 0,
                          modifiedTime = Instant.ofEpochSecond(2500),
                          modifiedBy = user.userId,
                      ),
                  currentYearProgress = emptyList(),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.TreesPlanted,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          target = 600,
                          systemValue = 300,
                          systemTime = Instant.ofEpochSecond(7000),
                          overrideValue = 800,
                          status = ReportIndicatorStatus.Achieved,
                          modifiedTime = Instant.ofEpochSecond(700),
                          modifiedBy = user.userId,
                      ),
                  currentYearProgress =
                      listOf(CumulativeIndicatorProgressModel(ReportQuarter.Q1, 800)),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.SpeciesPlanted,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
                          systemValue = 0,
                      ),
              ),
              ReportAutoCalculatedIndicatorModel(
                  indicator = AutoCalculatedIndicator.SurvivalRate,
                  entry =
                      ReportAutoCalculatedIndicatorEntryModel(
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
              projectIndicators = projectIndicators,
              commonIndicators = commonIndicators,
              autoCalculatedIndicators = autoCalculatedIndicators,
          )

      assertEquals(
          reportModel,
          store.fetchOne(reportId, includeIndicators = true),
          "Fetch one with indicators",
      )

      assertEquals(
          reportModel.copy(
              projectIndicators = emptyList(),
              commonIndicators = emptyList(),
              autoCalculatedIndicators = emptyList(),
          ),
          store.fetchOne(reportId, includeIndicators = false),
          "Fetch one without indicators",
      )
    }

    @Test
    fun `omits inactive indicators when fetching a single report`() {
      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted)

      val activeCommonId = insertCommonIndicator(name = "Active Common Indicator")
      insertCommonIndicator(name = "Inactive Common Indicator", active = false)

      val activeProjectId = insertProjectIndicator(name = "Active Project Indicator")
      insertProjectIndicator(name = "Inactive Project Indicator", active = false)

      // Mark SeedsCollected inactive via direct DB update
      dslContext
          .update(AUTO_CALCULATED_INDICATORS)
          .set(AUTO_CALCULATED_INDICATORS.ACTIVE, false)
          .where(AUTO_CALCULATED_INDICATORS.ID.eq(AutoCalculatedIndicator.SeedsCollected))
          .execute()

      val report = store.fetchOne(reportId, includeIndicators = true)

      assertEquals(
          listOf(activeCommonId),
          report.commonIndicators.map { it.indicator.id },
          "fetchOne should return only active common indicators",
      )
      assertEquals(
          listOf(activeProjectId),
          report.projectIndicators.map { it.indicator.id },
          "fetchOne should return only active project indicators",
      )
      assertFalse(
          report.autoCalculatedIndicators.any {
            it.indicator == AutoCalculatedIndicator.SeedsCollected
          },
          "fetchOne should not return inactive auto-calculated indicator SeedsCollected",
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
  inner class ReviewReportIndicators {
    @Test
    fun `throws exception for non-TFExpert users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.Submitted)

      assertThrows<AccessDeniedException>(message = "Read-only Global Role") {
        store.reviewReportIndicators(reportId = reportId)
      }

      deleteUserGlobalRole(role = GlobalRole.ReadOnly)
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      assertDoesNotThrow(message = "TF-Expert Global Role") {
        store.reviewReportIndicators(reportId = reportId)
      }
    }

    @Test
    fun `upserts values and internalComment for existing and non-existing report indicator rows`() {
      val otherUserId = insertUser()

      val commonIndicatorId1 =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              description = "Climate common indicator description",
              name = "Climate Common Indicator",
              refId = "2.1",
              level = IndicatorLevel.Process,
          )

      val commonIndicatorId2 =
          insertCommonIndicator(
              category = IndicatorCategory.Community,
              description = "Community indicator description",
              name = "Community Indicator",
              refId = "10.0",
              level = IndicatorLevel.Outcome,
          )

      val commonIndicatorId3 =
          insertCommonIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Project objectives indicator description",
              name = "Project Objectives Indicator",
              refId = "2.0",
              level = IndicatorLevel.Goal,
          )

      // This has no entry and will not have any updates
      insertCommonIndicator(
          category = IndicatorCategory.Biodiversity,
          description = "Biodiversity indicator description",
          name = "Biodiversity Indicator",
          refId = "7.0",
          level = IndicatorLevel.Goal,
      )

      val projectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Project Indicator description",
              name = "Project Indicator Name",
              refId = "2.0",
              level = IndicatorLevel.Process,
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

      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId1,
          year = 1970,
          target = 55,
      )
      insertReportCommonIndicator(
          reportId = reportId,
          indicatorId = commonIndicatorId1,
          value = 45,
          projectsComments = "Existing indicator 1 notes",
          status = ReportIndicatorStatus.OnTrack,
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
      )

      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId2,
          year = 1970,
          target = 30,
      )
      insertReportCommonIndicator(
          reportId = reportId,
          indicatorId = commonIndicatorId2,
          value = null,
          projectsComments = "Existing indicator 2 notes",
          progressNotes = "Existing indicator 2 internal comment",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          year = 1970,
          target = 1000,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 1200,
          systemTime = Instant.ofEpochSecond(4000),
          projectsComments = "Existing seeds collected indicator notes",
          progressNotes = "Existing seeds collected indicator internal comment",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SpeciesPlanted,
          year = 1970,
          target = 10,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.SpeciesPlanted,
          overrideValue = 15,
          systemValue = 12,
          systemTime = Instant.ofEpochSecond(5000),
          projectsComments = "Existing species planted indicator notes",
          progressNotes = "Existing species planted indicator internal comment",
          status = ReportIndicatorStatus.Unlikely,
          modifiedTime = Instant.ofEpochSecond(5000),
          modifiedBy = user.userId,
      )

      // At this point, the report has entries for indicator 1 and 2, no entry for indicator 3 and 4
      clock.instant = Instant.ofEpochSecond(9000)

      // We add new entries for indicator 2 and 3. Indicator 1 and 4 are not modified
      store.reviewReportIndicators(
          reportId = reportId,
          commonIndicatorEntries =
              mapOf(
                  commonIndicatorId2 to
                      ReportIndicatorEntryModel(
                          value = 88,
                          projectsComments = "New indicator 2 notes",
                          progressNotes = "New indicator 2 internal comment",
                          status = ReportIndicatorStatus.OnTrack,

                          // These fields are ignored
                          modifiedTime = Instant.EPOCH,
                          modifiedBy = UserId(99),
                      ),
                  commonIndicatorId3 to
                      ReportIndicatorEntryModel(
                          value = 45,
                          projectsComments = "New indicator 3 notes",
                          progressNotes = "New indicator 3 internal comment",
                      ),
              ),
          autoCalculatedIndicatorEntries =
              mapOf(
                  AutoCalculatedIndicator.SpeciesPlanted to
                      ReportIndicatorEntryModel(
                          value = 4,
                          status = null,
                          projectsComments = "New species planted indicator notes",
                          progressNotes = "New species planted indicator internal comment",
                      ),
                  AutoCalculatedIndicator.TreesPlanted to
                      ReportIndicatorEntryModel(
                          value = 45,
                          status = ReportIndicatorStatus.Unlikely,
                          projectsComments = "New trees planted indicator notes",
                          progressNotes = "New trees planted indicator internal comment",
                      ),
              ),
          projectIndicatorEntries =
              mapOf(
                  projectIndicatorId to
                      ReportIndicatorEntryModel(
                          value = 50,
                          projectsComments = "Project indicator notes",
                          progressNotes = "Project indicator internal comment",
                      ),
              ),
      )

      assertTableEquals(
          listOf(
              ReportCommonIndicatorsRecord(
                  reportId = reportId,
                  commonIndicatorId = commonIndicatorId1,
                  value = 45,
                  statusId = ReportIndicatorStatus.OnTrack,
                  projectsComments = "Existing indicator 1 notes",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = otherUserId,
              ),
              ReportCommonIndicatorsRecord(
                  reportId = reportId,
                  commonIndicatorId = commonIndicatorId2,
                  value = 88,
                  statusId = ReportIndicatorStatus.OnTrack,
                  projectsComments = "New indicator 2 notes",
                  progressNotes = "New indicator 2 internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportCommonIndicatorsRecord(
                  reportId = reportId,
                  commonIndicatorId = commonIndicatorId3,
                  value = 45,
                  projectsComments = "New indicator 3 notes",
                  progressNotes = "New indicator 3 internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              // Common indicator 4 is not inserted since there was no updates
          ),
          "Reports common indicators table",
      )

      assertTableEquals(
          listOf(
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SeedsCollected,
                  systemValue = 1200,
                  systemTime = Instant.ofEpochSecond(4000),
                  projectsComments = "Existing seeds collected indicator notes",
                  progressNotes = "Existing seeds collected indicator internal comment",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = user.userId,
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SpeciesPlanted,
                  systemValue = 12,
                  systemTime = Instant.ofEpochSecond(5000),
                  overrideValue = 4,
                  statusId = null,
                  projectsComments = "New species planted indicator notes",
                  progressNotes = "New species planted indicator internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.TreesPlanted,
                  overrideValue = 45,
                  statusId = ReportIndicatorStatus.Unlikely,
                  projectsComments = "New trees planted indicator notes",
                  progressNotes = "New trees planted indicator internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
          ),
          "Reports auto calculated indicators table",
      )

      assertTableEquals(
          listOf(
              ReportProjectIndicatorsRecord(
                  reportId = reportId,
                  projectIndicatorId = projectIndicatorId,
                  value = 50,
                  projectsComments = "Project indicator notes",
                  progressNotes = "Project indicator internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
          ),
          "Reports project indicators table",
      )

      assertTableEquals(
          ReportsRecord(
              id = reportId,
              configId = configId,
              projectId = projectId,
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
    fun `upserts values and targets for existing and non-existing report indicator rows`() {
      val otherUserId = insertUser()
      val commonIndicatorId1 =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              description = "Climate common indicator description",
              name = "Climate Common Indicator",
              refId = "2.1",
              level = IndicatorLevel.Process,
          )

      val commonIndicatorId2 =
          insertCommonIndicator(
              category = IndicatorCategory.Community,
              description = "Community indicator description",
              name = "Community Indicator",
              refId = "10.0",
              level = IndicatorLevel.Outcome,
          )

      val commonIndicatorId3 =
          insertCommonIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Project objectives indicator description",
              name = "Project Objectives Indicator",
              refId = "2.0",
              level = IndicatorLevel.Goal,
          )

      // This has no entry and will not have any updates
      insertCommonIndicator(
          category = IndicatorCategory.Biodiversity,
          description = "Biodiversity indicator description",
          name = "Biodiversity Indicator",
          refId = "7.0",
          level = IndicatorLevel.Goal,
      )

      val projectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Project Indicator description",
              name = "Project Indicator Name",
              refId = "2.0",
              level = IndicatorLevel.Process,
          )

      val configId = insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.NotSubmitted, createdBy = otherUserId)

      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId1,
          year = 1970,
          target = 55,
      )
      insertReportCommonIndicator(
          reportId = reportId,
          indicatorId = commonIndicatorId1,
          value = 45,
          projectsComments = "Existing indicator 1 notes",
          status = ReportIndicatorStatus.OnTrack,
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
      )

      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId2,
          year = 1970,
          target = 30,
      )
      insertReportCommonIndicator(
          reportId = reportId,
          indicatorId = commonIndicatorId2,
          value = null,
          projectsComments = "Existing indicator 2 notes",
          progressNotes = "Existing indicator 2 internal comment",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          year = 1970,
          target = 1000,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 1200,
          systemTime = Instant.ofEpochSecond(4000),
          projectsComments = "Existing seeds collected indicator notes",
          progressNotes = "Existing seeds collected indicator internal comment",
          modifiedTime = Instant.ofEpochSecond(3000),
          modifiedBy = user.userId,
      )

      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SpeciesPlanted,
          year = 1970,
          target = 10,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.SpeciesPlanted,
          overrideValue = 15,
          systemValue = 12,
          systemTime = Instant.ofEpochSecond(5000),
          projectsComments = "Existing species planted indicator notes",
          progressNotes = "Existing species planted indicator internal comment",
          status = ReportIndicatorStatus.Unlikely,
          modifiedTime = Instant.ofEpochSecond(5000),
          modifiedBy = user.userId,
      )

      // At this point, the report has entries for common indicator 1 and 2, and no entry for
      // common indicator 3 and 4

      clock.instant = Instant.ofEpochSecond(9000)

      // We add new entries for common indicator 2 and 3. Common indicator 1 and 4 are not modified.
      // We also add a new entry for project indicator
      store.updateReport(
          reportId = reportId,
          highlights = null,
          achievements = emptyList(),
          challenges = emptyList(),
          commonIndicatorEntries =
              mapOf(
                  commonIndicatorId2 to
                      ReportIndicatorEntryModel(
                          value = 88,
                          projectsComments = "New indicator 2 notes",
                          status = ReportIndicatorStatus.OnTrack,

                          // These fields are ignored
                          progressNotes = "Not permitted to write internal comment",
                          modifiedTime = Instant.EPOCH,
                          modifiedBy = UserId(99),
                      ),
                  commonIndicatorId3 to
                      ReportIndicatorEntryModel(
                          value = null,
                          projectsComments = "New indicator 3 notes",
                      ),
              ),
          autoCalculatedIndicatorEntries =
              mapOf(
                  AutoCalculatedIndicator.SpeciesPlanted to
                      ReportIndicatorEntryModel(
                          projectsComments = "New species planted indicator notes",
                          status = null,

                          // These fields are ignored
                          value = 4,
                          progressNotes = "New species planted indicator internal comment",
                          modifiedTime = Instant.EPOCH,
                          modifiedBy = UserId(99),
                      ),
                  AutoCalculatedIndicator.TreesPlanted to
                      ReportIndicatorEntryModel(
                          projectsComments = "New trees planted indicator notes",
                          status = ReportIndicatorStatus.Unlikely,

                          // These fields are ignored
                          value = 45,
                          progressNotes = "New trees planted indicator internal comment",
                          modifiedTime = Instant.EPOCH,
                          modifiedBy = UserId(99),
                      ),
              ),
          projectIndicatorEntries =
              mapOf(
                  projectIndicatorId to
                      ReportIndicatorEntryModel(
                          value = 50,
                          projectsComments = "Project indicator notes",
                      ),
              ),
      )

      assertTableEquals(
          listOf(
              ReportCommonIndicatorsRecord(
                  reportId = reportId,
                  commonIndicatorId = commonIndicatorId1,
                  value = 45,
                  statusId = ReportIndicatorStatus.OnTrack,
                  projectsComments = "Existing indicator 1 notes",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = otherUserId,
              ),
              ReportCommonIndicatorsRecord(
                  reportId = reportId,
                  commonIndicatorId = commonIndicatorId2,
                  value = 88,
                  statusId = ReportIndicatorStatus.OnTrack,
                  projectsComments = "New indicator 2 notes",
                  progressNotes = "Existing indicator 2 internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportCommonIndicatorsRecord(
                  reportId = reportId,
                  commonIndicatorId = commonIndicatorId3,
                  projectsComments = "New indicator 3 notes",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              // Common indicator 4 is not inserted since there was no updates
          ),
          "Reports common indicators table",
      )

      assertTableEquals(
          listOf(
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SeedsCollected,
                  systemValue = 1200,
                  systemTime = Instant.ofEpochSecond(4000),
                  projectsComments = "Existing seeds collected indicator notes",
                  progressNotes = "Existing seeds collected indicator internal comment",
                  modifiedTime = Instant.ofEpochSecond(3000),
                  modifiedBy = user.userId,
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SpeciesPlanted,
                  systemValue = 12,
                  systemTime = Instant.ofEpochSecond(5000),
                  statusId = null,
                  overrideValue = 15,
                  projectsComments = "New species planted indicator notes",
                  progressNotes = "Existing species planted indicator internal comment",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.TreesPlanted,
                  statusId = ReportIndicatorStatus.Unlikely,
                  projectsComments = "New trees planted indicator notes",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
          ),
          "Reports auto calculated indicators table",
      )

      assertTableEquals(
          listOf(
              ReportProjectIndicatorsRecord(
                  reportId = reportId,
                  projectIndicatorId = projectIndicatorId,
                  value = 50,
                  projectsComments = "Project indicator notes",
                  modifiedTime = Instant.ofEpochSecond(9000),
                  modifiedBy = user.userId,
              ),
          ),
          "Reports project indicators table",
      )

      assertTableEquals(
          ReportsRecord(
              id = reportId,
              configId = configId,
              projectId = projectId,
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
  inner class RefreshAutoCalculatedIndicatorValues {
    @Test
    fun `throws Access Denied Exception for non-TFExpert users`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      insertUserGlobalRole(role = GlobalRole.ReadOnly)

      insertProjectReportConfig()
      val reportId = insertReport(status = ReportStatus.Submitted)

      assertThrows<AccessDeniedException>(message = "Read-only Global Role") {
        store.refreshAutoCalculatedIndicatorValues(reportId, emptySet())
      }

      deleteUserGlobalRole(role = GlobalRole.ReadOnly)
      insertUserGlobalRole(role = GlobalRole.TFExpert)

      assertDoesNotThrow(message = "TF-Expert Global Role") {
        store.refreshAutoCalculatedIndicatorValues(reportId, emptySet())
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

      insertDataForAutoCalculatedIndicators(
          reportStartDate = LocalDate.of(2025, Month.JANUARY, 1),
          reportEndDate = LocalDate.of(2025, Month.MARCH, 31),
      )

      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 1000,
          systemTime = Instant.ofEpochSecond(3000),
          overrideValue = 74,
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.Seedlings,
          systemValue = 2000,
          systemTime = Instant.ofEpochSecond(3000),
          overrideValue = 98,
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.TreesPlanted,
          systemValue = 3000,
          systemTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      val existingReport = reportsDao.fetchOneById(reportId)!!

      clock.instant = Instant.ofEpochSecond(9000)
      store.refreshAutoCalculatedIndicatorValues(
          reportId,
          setOf(
              AutoCalculatedIndicator.Seedlings,
              AutoCalculatedIndicator.TreesPlanted,
              AutoCalculatedIndicator.SpeciesPlanted,
          ),
      )

      assertTableEquals(
          listOf(
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SeedsCollected,
                  systemValue = 1000,
                  systemTime = Instant.ofEpochSecond(3000),
                  overrideValue = 74,
                  modifiedBy = otherUserId,
                  modifiedTime = Instant.ofEpochSecond(3000),
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.Seedlings,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.TreesPlanted,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SpeciesPlanted,
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
    fun `inserts into or updates report auto calculated indicators table, sets override values to null`() {
      val otherUserId = insertUser()
      insertProjectReportConfig()
      val reportId =
          insertReport(
              status = ReportStatus.Submitted,
              startDate = LocalDate.of(2025, Month.JANUARY, 1),
              endDate = LocalDate.of(2025, Month.MARCH, 31),
          )

      insertDataForAutoCalculatedIndicators(
          reportStartDate = LocalDate.of(2025, Month.JANUARY, 1),
          reportEndDate = LocalDate.of(2025, Month.MARCH, 31),
      )

      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 1000,
          systemTime = Instant.ofEpochSecond(3000),
          overrideValue = 74,
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.Seedlings,
          systemValue = 2000,
          systemTime = Instant.ofEpochSecond(3000),
          overrideValue = 98,
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.TreesPlanted,
          systemValue = 3000,
          systemTime = Instant.ofEpochSecond(3000),
          modifiedBy = otherUserId,
          modifiedTime = Instant.ofEpochSecond(3000),
      )
      val existingReport = reportsDao.fetchOneById(reportId)!!

      clock.instant = Instant.ofEpochSecond(9000)
      store.refreshAutoCalculatedIndicatorValues(
          reportId,
          setOf(
              AutoCalculatedIndicator.Seedlings,
              AutoCalculatedIndicator.TreesPlanted,
              AutoCalculatedIndicator.SpeciesPlanted,
          ),
      )

      assertTableEquals(
          listOf(
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SeedsCollected,
                  systemValue = 1000,
                  systemTime = Instant.ofEpochSecond(3000),
                  overrideValue = 74,
                  modifiedBy = otherUserId,
                  modifiedTime = Instant.ofEpochSecond(3000),
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.Seedlings,
                  systemValue = 83,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.TreesPlanted,
                  systemValue = 27,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SpeciesPlanted,
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
      insertAutoCalculatedIndicatorTargetsForReport(reportId)
      insertDataForAutoCalculatedIndicators(
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
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SeedsCollected,
                  systemValue = 98,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.HectaresPlanted,
                  systemValue = 60,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.Seedlings,
                  systemValue = 83,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.TreesPlanted,
                  systemValue = 27,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SpeciesPlanted,
                  systemValue = 1,
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SurvivalRate,
                  systemValue =
                      (sitesLiveSum * 100.0 / (site1T0Density + site2T0Density)).roundToInt(),
                  systemTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
          ),
          "Report auto calculated indicators",
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
              reportingStartDate = LocalDate.of(2025, Month.JANUARY, 7),
              reportingEndDate = LocalDate.of(2031, Month.MAY, 9),
          )

      val otherProjectConfigId =
          insertProjectReportConfig(
              projectId = otherProjectId,
              reportingStartDate = LocalDate.of(2031, Month.JANUARY, 18),
              reportingEndDate = LocalDate.of(2039, Month.MAY, 31),
          )

      val projectConfigModel =
          ExistingProjectReportConfigModel(
              id = projectConfigId,
              projectId = projectId,
              reportingStartDate = LocalDate.of(2025, Month.JANUARY, 7),
              reportingEndDate = LocalDate.of(2031, Month.MAY, 9),
              logframeUrl = null,
          )

      val otherProjectConfigModel =
          ExistingProjectReportConfigModel(
              id = otherProjectConfigId,
              projectId = otherProjectId,
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
              reportingStartDate = LocalDate.of(2025, Month.MAY, 5),
              reportingEndDate = LocalDate.of(2028, Month.MARCH, 2),
              logframeUrl = null,
          )

      assertThrows<AccessDeniedException> { store.insertProjectReportConfig(config) }
    }

    @Test
    fun `inserts config record and creates reports`() {
      clock.instant = Instant.ofEpochSecond(9000)

      deleteProjectAcceleratorDetails(projectId)
      val config =
          NewProjectReportConfigModel(
              id = null,
              projectId = projectId,
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
              reportingStartDate = LocalDate.of(2021, Month.MARCH, 13),
              reportingEndDate = LocalDate.of(2021, Month.SEPTEMBER, 9),
          )

      // Reports are added in random order

      val q2ReportId =
          insertReport(
              configId = configId,
              projectId = projectId,
              quarter = ReportQuarter.Q2,
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
              status = ReportStatus.NotNeeded,
              startDate = LocalDate.of(2020, Month.MARCH, 13),
              endDate = LocalDate.of(2020, Month.MARCH, 31),
          )

      val q4ReportId =
          insertReport(
              configId = configId,
              projectId = projectId,
              quarter = ReportQuarter.Q4,
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
    private val commonIndicatorId1 by lazy { insertCommonIndicator() }
    private val commonIndicatorId2 by lazy { insertCommonIndicator() }
    private val commonIndicatorNullValueId by lazy { insertCommonIndicator() }
    private val commonIndicatorNotPublishableId by lazy {
      insertCommonIndicator(isPublishable = false)
    }

    private val projectIndicatorId1 by lazy { insertProjectIndicator() }
    private val projectIndicatorId2 by lazy { insertProjectIndicator() }
    private val projectIndicatorNullValueId by lazy { insertProjectIndicator() }
    private val projectIndicatorNotPublishableId by lazy {
      insertProjectIndicator(isPublishable = false)
    }

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

      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId1,
          year = 2030,
          target = 10,
      )
      insertReportCommonIndicator(
          reportId = reportId,
          indicatorId = commonIndicatorId1,
          status = ReportIndicatorStatus.Achieved,
          value = 10,
          projectsComments = null,
          progressNotes = "Common Indicator 1 Progress notes",
      )

      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId2,
          year = 2030,
          target = 20,
      )
      insertReportCommonIndicator(
          reportId = reportId,
          indicatorId = commonIndicatorId2,
          status = ReportIndicatorStatus.OnTrack,
          value = 19,
          projectsComments = "Common Indicator 2 Underperformance",
      )

      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorNullValueId,
          year = 2030,
          target = 999,
      )
      insertReportCommonIndicator(
          reportId = reportId,
          indicatorId = commonIndicatorNullValueId,
          value = null,
      )

      insertReportCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorNotPublishableId,
          year = 2030,
          target = 999,
      )
      insertReportCommonIndicator(
          reportId = reportId,
          indicatorId = commonIndicatorNotPublishableId,
          value = 999,
      )

      insertReportProjectIndicatorTarget(
          projectIndicatorId = projectIndicatorId1,
          year = 2030,
          target = 30,
      )
      insertReportProjectIndicator(
          reportId = reportId,
          indicatorId = projectIndicatorId1,
          status = ReportIndicatorStatus.Achieved,
          value = 30,
          projectsComments = null,
          progressNotes = "Project Indicator 1 Progress notes",
      )

      insertReportProjectIndicatorTarget(
          projectIndicatorId = projectIndicatorId2,
          year = 2030,
          target = 40,
      )
      insertReportProjectIndicator(
          reportId = reportId,
          indicatorId = projectIndicatorId2,
          status = ReportIndicatorStatus.Unlikely,
          value = 39,
          projectsComments = "Project Indicator 2 Underperformance",
      )

      insertReportProjectIndicatorTarget(
          projectIndicatorId = projectIndicatorNullValueId,
          year = 2030,
          target = 999,
      )
      insertReportProjectIndicator(
          reportId = reportId,
          indicatorId = projectIndicatorNullValueId,
          value = null,
      )

      insertReportProjectIndicatorTarget(
          projectIndicatorId = projectIndicatorNotPublishableId,
          year = 2030,
          target = 999,
      )
      insertReportProjectIndicator(
          reportId = reportId,
          indicatorId = projectIndicatorNotPublishableId,
          value = 999,
      )

      // Seeds Collected is not publishable
      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          year = 2030,
          target = 999,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.SeedsCollected,
          status = ReportIndicatorStatus.Achieved,
          systemValue = 999,
      )

      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.Seedlings,
          year = 2030,
          target = 50,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.Seedlings,
          status = ReportIndicatorStatus.OnTrack,
          overrideValue = 49,
          systemValue = 39,
          projectsComments = "Seedlings underperformance justification",
          progressNotes = "Seedlings progress notes",
      )

      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SpeciesPlanted,
          year = 2030,
          target = 10,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.SpeciesPlanted,
          status = ReportIndicatorStatus.Achieved,
          systemValue = 10,
      )

      // Indicators can be published even with no target set
      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.TreesPlanted,
          year = 2030,
          target = null,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.TreesPlanted,
          status = ReportIndicatorStatus.Achieved,
          systemValue = 100,
      )

      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SurvivalRate,
          year = 2030,
          target = 0,
      )
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.SurvivalRate,
          status = ReportIndicatorStatus.Unlikely,
          systemValue = 51,
      )

      // Baseline and end-of-project targets
      insertProjectIndicatorBaselineTarget(
          projectIndicatorId = projectIndicatorId1,
          baseline = 10,
          endTarget = 100,
      )
      insertProjectIndicatorBaselineTarget(
          projectIndicatorId = projectIndicatorId2,
          baseline = 20,
          endTarget = 200,
      )
      insertProjectIndicatorBaselineTarget(
          projectIndicatorId = projectIndicatorNotPublishableId,
          baseline = 30,
          endTarget = 300,
          // Not expected to be published because the indicator is not publishable
      )
      insertCommonIndicatorBaselineTarget(
          commonIndicatorId = commonIndicatorId1,
          baseline = 5,
          endTarget = 50,
      )
      insertCommonIndicatorBaselineTarget(
          commonIndicatorId = commonIndicatorNullValueId,
          baseline = null,
          endTarget = 15,
      )
      insertAutoCalculatedIndicatorBaselineTarget(
          indicator = AutoCalculatedIndicator.Seedlings,
          baseline = 25,
          endTarget = 250,
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

      insertPublishedReportCommonIndicator(
          indicatorId = commonIndicatorId1,
          value = 100,
          progressNotes = "Existing progress notes",
          projectsComments = "Existing underperformance justification",
      )
      insertPublishedReportCommonIndicator(
          indicatorId = commonIndicatorNullValueId,
          value = 100,
      )
      insertPublishedReportCommonIndicator(
          indicatorId = commonIndicatorNotPublishableId,
          value = 100,
      )

      insertPublishedReportProjectIndicator(
          indicatorId = projectIndicatorId1,
          value = 100,
          progressNotes = "Existing progress notes",
          projectsComments = "Existing underperformance justification",
      )
      insertPublishedReportProjectIndicator(
          indicatorId = projectIndicatorId2,
          value = 100,
          projectsComments = "Existing underperformance justification",
      )
      insertPublishedReportProjectIndicator(
          indicatorId = projectIndicatorNotPublishableId,
          value = 100,
      )

      insertPublishedReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          value = 100,
      )
      insertPublishedReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.Seedlings,
          value = 100,
          progressNotes = "Existing progress notes",
          projectsComments = "Existing underperformance justification",
      )
      insertPublishedReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.TreesPlanted,
          value = 100,
          projectsComments = "Existing underperformance justification",
      )

      insertPublishedProjectIndicatorBaseline(
          projectIndicatorId = projectIndicatorId1,
          baseline = 999,
          endTarget = 9999,
      )
      insertPublishedCommonIndicatorBaseline(
          commonIndicatorId = commonIndicatorId1,
          baseline = 999,
          endTarget = 9999,
      )
      insertPublishedAutoCalculatedIndicatorBaseline(
          indicator = AutoCalculatedIndicator.Seedlings,
          baseline = 999,
          endTarget = 9999,
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

    @Test
    fun `only publishes active indicators and deletes inactive indicators`() {
      // Insert inactive common and project indicators that have values so they would be published
      // if they were active.
      val inactiveCommonIndicatorId = insertCommonIndicator(active = false, isPublishable = true)
      val inactiveProjectIndicatorId = insertProjectIndicator(active = false, isPublishable = true)

      insertReportCommonIndicatorTarget(
          commonIndicatorId = inactiveCommonIndicatorId,
          year = 2030,
          target = 55,
      )
      insertReportCommonIndicator(
          reportId = reportId,
          indicatorId = inactiveCommonIndicatorId,
          value = 55,
          status = ReportIndicatorStatus.Achieved,
      )

      insertReportProjectIndicatorTarget(
          projectIndicatorId = inactiveProjectIndicatorId,
          year = 2030,
          target = 66,
      )
      insertReportProjectIndicator(
          reportId = reportId,
          indicatorId = inactiveProjectIndicatorId,
          value = 66,
          status = ReportIndicatorStatus.Achieved,
      )

      // Pre-insert a published report with rows for the inactive indicators so we can verify they
      // are deleted on re-publish.
      insertPublishedReport(reportId = reportId, projectId = projectId)
      insertPublishedReportCommonIndicator(
          indicatorId = inactiveCommonIndicatorId,
          value = 55,
      )
      insertPublishedReportProjectIndicator(
          indicatorId = inactiveProjectIndicatorId,
          value = 66,
      )

      clock.instant = Instant.ofEpochSecond(10000)
      store.publishReport(reportId)

      assertTableEquals(
          listOf(
              PublishedReportCommonIndicatorsRecord(
                  reportId = reportId,
                  commonIndicatorId = commonIndicatorId1,
                  statusId = ReportIndicatorStatus.Achieved,
                  value = 10,
                  progressNotes = "Common Indicator 1 Progress notes",
              ),
              PublishedReportCommonIndicatorsRecord(
                  reportId = reportId,
                  commonIndicatorId = commonIndicatorId2,
                  statusId = ReportIndicatorStatus.OnTrack,
                  value = 19,
                  projectsComments = "Common Indicator 2 Underperformance",
              ),
          ),
          "Published report common indicators table",
      )

      assertTableEquals(
          listOf(
              PublishedReportProjectIndicatorsRecord(
                  reportId = reportId,
                  projectIndicatorId = projectIndicatorId1,
                  statusId = ReportIndicatorStatus.Achieved,
                  value = 30,
                  progressNotes = "Project Indicator 1 Progress notes",
              ),
              PublishedReportProjectIndicatorsRecord(
                  reportId = reportId,
                  projectIndicatorId = projectIndicatorId2,
                  statusId = ReportIndicatorStatus.Unlikely,
                  value = 39,
                  projectsComments = "Project Indicator 2 Underperformance",
              ),
          ),
          "Published report project indicators table",
      )
    }

    // Helper function to validate the report from setupReport() is in the published reports tables
    private fun assertPublishedReport(publishedBy: UserId, publishedTime: Instant) {
      assertTableEquals(
          PublishedReportsRecord(
              reportId = reportId,
              projectId = projectId,
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
              PublishedReportCommonIndicatorsRecord(
                  reportId = reportId,
                  commonIndicatorId = commonIndicatorId1,
                  statusId = ReportIndicatorStatus.Achieved,
                  value = 10,
                  projectsComments = null,
                  progressNotes = "Common Indicator 1 Progress notes",
              ),
              PublishedReportCommonIndicatorsRecord(
                  reportId = reportId,
                  commonIndicatorId = commonIndicatorId2,
                  statusId = ReportIndicatorStatus.OnTrack,
                  value = 19,
                  projectsComments = "Common Indicator 2 Underperformance",
              ),
          ),
          "Published report common indicators table",
      )

      assertTableEquals(
          listOf(
              PublishedReportProjectIndicatorsRecord(
                  reportId = reportId,
                  projectIndicatorId = projectIndicatorId1,
                  statusId = ReportIndicatorStatus.Achieved,
                  value = 30,
                  projectsComments = null,
                  progressNotes = "Project Indicator 1 Progress notes",
              ),
              PublishedReportProjectIndicatorsRecord(
                  reportId = reportId,
                  projectIndicatorId = projectIndicatorId2,
                  statusId = ReportIndicatorStatus.Unlikely,
                  value = 39,
                  projectsComments = "Project Indicator 2 Underperformance",
              ),
          ),
          "Published report project indicators table",
      )

      assertTableEquals(
          listOf(
              PublishedReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.Seedlings,
                  statusId = ReportIndicatorStatus.OnTrack,
                  value = 49,
                  projectsComments = "Seedlings underperformance justification",
                  progressNotes = "Seedlings progress notes",
              ),
              PublishedReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SpeciesPlanted,
                  statusId = ReportIndicatorStatus.Achieved,
                  value = 10,
              ),
              PublishedReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.TreesPlanted,
                  statusId = ReportIndicatorStatus.Achieved,
                  value = 100,
              ),
              PublishedReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SurvivalRate,
                  statusId = ReportIndicatorStatus.Unlikely,
                  value = 51,
              ),
              PublishedReportAutoCalculatedIndicatorsRecord(
                  reportId = reportId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.HectaresPlanted,
                  statusId = null,
                  value = 0,
              ),
          ),
          "Published report auto calculated indicators table",
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
              PublishedProjectIndicatorTargetsRecord(
                  projectId = projectId,
                  projectIndicatorId = projectIndicatorId1,
                  year = 2030,
                  target = 30,
              ),
              PublishedProjectIndicatorTargetsRecord(
                  projectId = projectId,
                  projectIndicatorId = projectIndicatorId2,
                  year = 2030,
                  target = 40,
              ),
              PublishedProjectIndicatorTargetsRecord(
                  projectId = projectId,
                  projectIndicatorId = projectIndicatorNullValueId,
                  year = 2030,
                  target = 999,
              ),
          ),
          "Published project indicator targets table",
      )

      // Assert published indicator targets for the report year (2030)
      assertTableEquals(
          listOf(
              PublishedCommonIndicatorTargetsRecord(
                  projectId = projectId,
                  commonIndicatorId = commonIndicatorId1,
                  year = 2030,
                  target = 10,
              ),
              PublishedCommonIndicatorTargetsRecord(
                  projectId = projectId,
                  commonIndicatorId = commonIndicatorId2,
                  year = 2030,
                  target = 20,
              ),
              PublishedCommonIndicatorTargetsRecord(
                  projectId = projectId,
                  commonIndicatorId = commonIndicatorNullValueId,
                  year = 2030,
                  target = 999,
              ),
          ),
          "Published common indicator targets table",
      )

      assertTableEquals(
          listOf(
              PublishedAutoCalculatedIndicatorTargetsRecord(
                  projectId = projectId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.Seedlings,
                  year = 2030,
                  target = 50,
              ),
              PublishedAutoCalculatedIndicatorTargetsRecord(
                  projectId = projectId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SpeciesPlanted,
                  year = 2030,
                  target = 10,
              ),
              PublishedAutoCalculatedIndicatorTargetsRecord(
                  projectId = projectId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.HectaresPlanted,
                  year = 2030,
                  target = null,
              ),
              PublishedAutoCalculatedIndicatorTargetsRecord(
                  projectId = projectId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.TreesPlanted,
                  year = 2030,
                  target = null,
              ),
              PublishedAutoCalculatedIndicatorTargetsRecord(
                  projectId = projectId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SurvivalRate,
                  year = 2030,
                  target = 0,
              ),
          ),
          "Published auto calculated indicator targets table",
      )

      assertTableEquals(
          listOf(
              PublishedProjectIndicatorBaselinesRecord(
                  projectId = projectId,
                  projectIndicatorId = projectIndicatorId1,
                  baseline = BigDecimal(10),
                  endTarget = BigDecimal(100),
              ),
              PublishedProjectIndicatorBaselinesRecord(
                  projectId = projectId,
                  projectIndicatorId = projectIndicatorId2,
                  baseline = BigDecimal(20),
                  endTarget = BigDecimal(200),
              ),
          ),
          "Published project indicator baselines table",
      )

      assertTableEquals(
          listOf(
              PublishedCommonIndicatorBaselinesRecord(
                  projectId = projectId,
                  commonIndicatorId = commonIndicatorId1,
                  baseline = BigDecimal(5),
                  endTarget = BigDecimal(50),
              ),
              PublishedCommonIndicatorBaselinesRecord(
                  projectId = projectId,
                  commonIndicatorId = commonIndicatorNullValueId,
                  baseline = null,
                  endTarget = BigDecimal(15),
              ),
          ),
          "Published common indicator baselines table",
      )

      assertTableEquals(
          listOf(
              PublishedAutoCalculatedIndicatorBaselinesRecord(
                  projectId = projectId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.Seedlings,
                  baseline = BigDecimal(25),
                  endTarget = BigDecimal(250),
              ),
          ),
          "Published auto calculated indicator baselines table",
      )
    }
  }

  private fun getRandomDate(startDate: LocalDate, endDate: LocalDate): LocalDate {
    val startEpochDay = startDate.toEpochDay()
    val endEpochDay = endDate.toEpochDay()

    val randomDay = Random.nextLong(startEpochDay, endEpochDay + 1)

    return LocalDate.ofEpochDay(randomDay)
  }

  private fun insertDataForAutoCalculatedIndicators(
      reportStartDate: LocalDate,
      reportEndDate: LocalDate,
  ) {
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

    // This will count towards the seedlings indicator, but not the trees planted indicator.
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

  private fun insertAutoCalculatedIndicatorTargetsForReport(reportId: ReportId) {
    AutoCalculatedIndicator.entries.forEach { indicator ->
      insertReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = indicator,
      )
    }
  }

  @Nested
  inner class UpdateProjectIndicatorTarget {
    @Test
    fun `inserts new target`() {
      val projectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      store.updateProjectIndicatorTarget(
          projectId = projectId,
          year = 2024,
          indicatorId = projectIndicatorId,
          target = 100,
      )

      val targets = reportProjectIndicatorTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertEquals(projectId, targets[0].projectId)
      assertEquals(projectIndicatorId, targets[0].projectIndicatorId)
      assertEquals(2024, targets[0].year)
      assertEquals(100, targets[0].target)
    }

    @Test
    fun `updates existing target`() {
      val projectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      insertReportProjectIndicatorTarget(
          projectId = projectId,
          projectIndicatorId = projectIndicatorId,
          year = 2024,
          target = 100,
      )

      store.updateProjectIndicatorTarget(
          projectId = projectId,
          year = 2024,
          indicatorId = projectIndicatorId,
          target = 150,
      )

      val targets = reportProjectIndicatorTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertEquals(150, targets[0].target)
    }

    @Test
    fun `allows null target`() {
      val projectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      store.updateProjectIndicatorTarget(
          projectId = projectId,
          year = 2024,
          indicatorId = projectIndicatorId,
          target = null,
      )

      val targets = reportProjectIndicatorTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertNull(targets[0].target)
    }

    @Test
    fun `requires permission to update project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Manager)

      val projectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      assertThrows<AccessDeniedException> {
        store.updateProjectIndicatorTarget(
            projectId = projectId,
            year = 2024,
            indicatorId = projectIndicatorId,
            target = 100,
        )
      }
    }
  }

  @Nested
  inner class UpdateCommonIndicatorTarget {
    @Test
    fun `inserts new target`() {
      val commonIndicatorId =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      store.updateCommonIndicatorTarget(
          projectId = projectId,
          year = 2024,
          indicatorId = commonIndicatorId,
          target = 200,
      )

      val targets = reportCommonIndicatorTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertEquals(projectId, targets[0].projectId)
      assertEquals(commonIndicatorId, targets[0].commonIndicatorId)
      assertEquals(2024, targets[0].year)
      assertEquals(200, targets[0].target)
    }

    @Test
    fun `updates existing target`() {
      val commonIndicatorId =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      insertReportCommonIndicatorTarget(
          projectId = projectId,
          commonIndicatorId = commonIndicatorId,
          year = 2024,
          target = 200,
      )

      store.updateCommonIndicatorTarget(
          projectId = projectId,
          year = 2024,
          indicatorId = commonIndicatorId,
          target = 250,
      )

      val targets = reportCommonIndicatorTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertEquals(250, targets[0].target)
    }

    @Test
    fun `allows null target`() {
      val commonIndicatorId =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      store.updateCommonIndicatorTarget(
          projectId = projectId,
          year = 2024,
          indicatorId = commonIndicatorId,
          target = null,
      )

      val targets = reportCommonIndicatorTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertNull(targets[0].target)
    }

    @Test
    fun `requires permission to update project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Manager)

      val commonIndicatorId =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      assertThrows<AccessDeniedException> {
        store.updateCommonIndicatorTarget(
            projectId = projectId,
            year = 2024,
            indicatorId = commonIndicatorId,
            target = 200,
        )
      }
    }
  }

  @Nested
  inner class UpdateAutoCalculatedIndicatorTarget {
    @Test
    fun `inserts new target`() {
      store.updateAutoCalculatedIndicatorTarget(
          projectId = projectId,
          year = 2024,
          indicatorId = AutoCalculatedIndicator.TreesPlanted,
          target = 500,
      )

      val targets = reportAutoCalculatedIndicatorTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertEquals(projectId, targets[0].projectId)
      assertEquals(AutoCalculatedIndicator.TreesPlanted, targets[0].autoCalculatedIndicatorId)
      assertEquals(2024, targets[0].year)
      assertEquals(500, targets[0].target)
    }

    @Test
    fun `updates existing target`() {
      insertReportAutoCalculatedIndicatorTarget(
          projectId = projectId,
          indicator = AutoCalculatedIndicator.TreesPlanted,
          year = 2024,
          target = 500,
      )

      store.updateAutoCalculatedIndicatorTarget(
          projectId = projectId,
          year = 2024,
          indicatorId = AutoCalculatedIndicator.TreesPlanted,
          target = 600,
      )

      val targets = reportAutoCalculatedIndicatorTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertEquals(600, targets[0].target)
    }

    @Test
    fun `allows null target`() {
      store.updateAutoCalculatedIndicatorTarget(
          projectId = projectId,
          year = 2024,
          indicatorId = AutoCalculatedIndicator.TreesPlanted,
          target = null,
      )

      val targets = reportAutoCalculatedIndicatorTargetsDao.findAll()
      assertEquals(1, targets.size)
      assertNull(targets[0].target)
    }

    @Test
    fun `requires permission to update project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Manager)

      assertThrows<AccessDeniedException> {
        store.updateAutoCalculatedIndicatorTarget(
            projectId = projectId,
            year = 2024,
            indicatorId = AutoCalculatedIndicator.TreesPlanted,
            target = 500,
        )
      }
    }
  }

  @Nested
  inner class FetchReportProjectIndicatorTargets {
    @Test
    fun `requires permission to read project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> { store.fetchReportProjectIndicatorTargets(projectId) }
    }

    @Test
    fun `returns all project indicator targets for a project`() {
      val projectIndicatorId1 =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Test indicator 1",
              name = "Test Indicator 1",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )
      val projectIndicatorId2 =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Test indicator 2",
              name = "Test Indicator 2",
              refId = "2.0",
              level = IndicatorLevel.Process,
          )

      insertReportProjectIndicatorTarget(
          projectId = projectId,
          projectIndicatorId = projectIndicatorId1,
          year = 2024,
          target = 100,
      )
      insertReportProjectIndicatorTarget(
          projectId = projectId,
          projectIndicatorId = projectIndicatorId2,
          year = 2024,
          target = 200,
      )
      insertReportProjectIndicatorTarget(
          projectId = projectId,
          projectIndicatorId = projectIndicatorId1,
          year = 2025,
          target = 150,
      )

      val targets = store.fetchReportProjectIndicatorTargets(projectId)

      assertEquals(
          listOf(
              ReportProjectIndicatorTargetModel(projectIndicatorId1, 100, 2024),
              ReportProjectIndicatorTargetModel(projectIndicatorId2, 200, 2024),
              ReportProjectIndicatorTargetModel(projectIndicatorId1, 150, 2025),
          ),
          targets,
      )
    }
  }

  @Nested
  inner class FetchReportCommonIndicatorTargets {
    @Test
    fun `requires permission to read project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> { store.fetchReportCommonIndicatorTargets(projectId) }
    }

    @Test
    fun `returns all common indicator targets for a project`() {
      val commonIndicatorId1 =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              description = "Test indicator 1",
              name = "Test Indicator 1",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )
      val commonIndicatorId2 =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              description = "Test indicator 2",
              name = "Test Indicator 2",
              refId = "2.0",
              level = IndicatorLevel.Process,
          )

      insertReportCommonIndicatorTarget(
          projectId = projectId,
          commonIndicatorId = commonIndicatorId1,
          year = 2024,
          target = 300,
      )
      insertReportCommonIndicatorTarget(
          projectId = projectId,
          commonIndicatorId = commonIndicatorId2,
          year = 2024,
          target = 400,
      )
      insertReportCommonIndicatorTarget(
          projectId = projectId,
          commonIndicatorId = commonIndicatorId1,
          year = 2025,
          target = 350,
      )

      val targets = store.fetchReportCommonIndicatorTargets(projectId)

      assertEquals(
          listOf(
              ReportCommonIndicatorTargetModel(commonIndicatorId1, 300, 2024),
              ReportCommonIndicatorTargetModel(commonIndicatorId2, 400, 2024),
              ReportCommonIndicatorTargetModel(commonIndicatorId1, 350, 2025),
          ),
          targets,
      )
    }
  }

  @Nested
  inner class FetchReportAutoCalculatedIndicatorTargets {
    @Test
    fun `requires permission to read project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> {
        store.fetchReportAutoCalculatedIndicatorTargets(projectId)
      }
    }

    @Test
    fun `returns all auto calculated indicator targets for a project`() {
      insertReportAutoCalculatedIndicatorTarget(
          projectId = projectId,
          indicator = AutoCalculatedIndicator.TreesPlanted,
          year = 2024,
          target = 500,
      )
      insertReportAutoCalculatedIndicatorTarget(
          projectId = projectId,
          indicator = AutoCalculatedIndicator.SeedsCollected,
          year = 2024,
          target = 1000,
      )
      insertReportAutoCalculatedIndicatorTarget(
          projectId = projectId,
          indicator = AutoCalculatedIndicator.TreesPlanted,
          year = 2025,
          target = 600,
      )

      val targets = store.fetchReportAutoCalculatedIndicatorTargets(projectId)

      assertEquals(
          listOf(
              ReportAutoCalculatedIndicatorTargetModel(
                  AutoCalculatedIndicator.SeedsCollected,
                  1000,
                  2024,
              ),
              ReportAutoCalculatedIndicatorTargetModel(
                  AutoCalculatedIndicator.TreesPlanted,
                  500,
                  2024,
              ),
              ReportAutoCalculatedIndicatorTargetModel(
                  AutoCalculatedIndicator.TreesPlanted,
                  600,
                  2025,
              ),
          ),
          targets,
      )
    }
  }

  @Nested
  inner class UpdateProjectIndicatorBaselineTarget {
    @Test
    fun `inserts new baseline target`() {
      val projectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      store.updateProjectIndicatorBaselineTarget(
          projectId = projectId,
          indicatorId = projectIndicatorId,
          baseline = BigDecimal("50"),
          endOfProjectTarget = BigDecimal("500"),
      )

      assertTableEquals(
          ProjectIndicatorTargetsRecord(
              projectId = projectId,
              projectIndicatorId = projectIndicatorId,
              baseline = BigDecimal("50"),
              endTarget = BigDecimal("500"),
          )
      )
    }

    @Test
    fun `updates existing baseline target`() {
      val projectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )
      insertProjectIndicatorBaselineTarget(baseline = 50, endTarget = 500)

      val otherProjectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Other indicator",
              name = "Other Indicator",
              refId = "2.0",
              level = IndicatorLevel.Process,
          )
      insertProjectIndicatorBaselineTarget(baseline = 10, endTarget = 100)

      store.updateProjectIndicatorBaselineTarget(
          projectId = projectId,
          indicatorId = projectIndicatorId,
          baseline = BigDecimal("75"),
          endOfProjectTarget = BigDecimal("750"),
      )

      assertTableEquals(
          listOf(
              ProjectIndicatorTargetsRecord(
                  projectId = projectId,
                  projectIndicatorId = projectIndicatorId,
                  baseline = BigDecimal("75"),
                  endTarget = BigDecimal("750"),
              ),
              ProjectIndicatorTargetsRecord(
                  projectId = projectId,
                  projectIndicatorId = otherProjectIndicatorId,
                  baseline = BigDecimal("10"),
                  endTarget = BigDecimal("100"),
              ),
          )
      )
    }

    @Test
    fun `allows null values`() {
      val projectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      store.updateProjectIndicatorBaselineTarget(
          projectId = projectId,
          indicatorId = projectIndicatorId,
          baseline = null,
          endOfProjectTarget = null,
      )

      assertTableEquals(
          ProjectIndicatorTargetsRecord(
              projectId = projectId,
              projectIndicatorId = projectIndicatorId,
          )
      )
    }

    @Test
    fun `requires permission to update project report targets`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Manager)

      val projectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      assertThrows<AccessDeniedException> {
        store.updateProjectIndicatorBaselineTarget(
            projectId = projectId,
            indicatorId = projectIndicatorId,
            baseline = BigDecimal("50"),
            endOfProjectTarget = BigDecimal("500"),
        )
      }
    }
  }

  @Nested
  inner class UpdateCommonIndicatorBaselineTarget {
    @Test
    fun `inserts new baseline target`() {
      val commonIndicatorId =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      store.updateCommonIndicatorBaselineTarget(
          projectId = projectId,
          indicatorId = commonIndicatorId,
          baseline = BigDecimal("75"),
          endOfProjectTarget = BigDecimal("750"),
      )

      assertTableEquals(
          CommonIndicatorTargetsRecord(
              projectId = projectId,
              commonIndicatorId = commonIndicatorId,
              baseline = BigDecimal("75"),
              endTarget = BigDecimal("750"),
          )
      )
    }

    @Test
    fun `updates existing baseline target`() {
      val commonIndicatorId =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )
      insertCommonIndicatorBaselineTarget(baseline = 75, endTarget = 750)

      val otherCommonIndicatorId =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              description = "Other indicator",
              name = "Other Indicator",
              refId = "2.0",
              level = IndicatorLevel.Process,
          )
      insertCommonIndicatorBaselineTarget(baseline = 20, endTarget = 200)

      store.updateCommonIndicatorBaselineTarget(
          projectId = projectId,
          indicatorId = commonIndicatorId,
          baseline = BigDecimal("100"),
          endOfProjectTarget = BigDecimal("1000"),
      )

      assertTableEquals(
          listOf(
              CommonIndicatorTargetsRecord(
                  projectId = projectId,
                  commonIndicatorId = commonIndicatorId,
                  baseline = BigDecimal("100"),
                  endTarget = BigDecimal("1000"),
              ),
              CommonIndicatorTargetsRecord(
                  projectId = projectId,
                  commonIndicatorId = otherCommonIndicatorId,
                  baseline = BigDecimal("20"),
                  endTarget = BigDecimal("200"),
              ),
          )
      )
    }

    @Test
    fun `allows null values`() {
      val commonIndicatorId =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      store.updateCommonIndicatorBaselineTarget(
          projectId = projectId,
          indicatorId = commonIndicatorId,
          baseline = null,
          endOfProjectTarget = null,
      )

      assertTableEquals(
          CommonIndicatorTargetsRecord(
              projectId = projectId,
              commonIndicatorId = commonIndicatorId,
          )
      )
    }

    @Test
    fun `requires permission to update project report targets`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Manager)

      val commonIndicatorId =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
              level = IndicatorLevel.Process,
          )

      assertThrows<AccessDeniedException> {
        store.updateCommonIndicatorBaselineTarget(
            projectId = projectId,
            indicatorId = commonIndicatorId,
            baseline = BigDecimal("75"),
            endOfProjectTarget = BigDecimal("750"),
        )
      }
    }
  }

  @Nested
  inner class UpdateAutoCalculatedIndicatorBaselineTarget {
    @Test
    fun `inserts new baseline target`() {
      store.updateAutoCalculatedIndicatorBaselineTarget(
          projectId = projectId,
          indicator = AutoCalculatedIndicator.TreesPlanted,
          baseline = BigDecimal("200"),
          endOfProjectTarget = BigDecimal("2000"),
      )

      assertTableEquals(
          AutoCalculatedIndicatorTargetsRecord(
              projectId = projectId,
              autoCalculatedIndicatorId = AutoCalculatedIndicator.TreesPlanted,
              baseline = BigDecimal("200"),
              endTarget = BigDecimal("2000"),
          )
      )
    }

    @Test
    fun `updates existing baseline target`() {
      insertAutoCalculatedIndicatorBaselineTarget(
          indicator = AutoCalculatedIndicator.TreesPlanted,
          baseline = 200,
          endTarget = 2000,
      )
      insertAutoCalculatedIndicatorBaselineTarget(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          baseline = 100,
          endTarget = 1000,
      )

      store.updateAutoCalculatedIndicatorBaselineTarget(
          projectId = projectId,
          indicator = AutoCalculatedIndicator.TreesPlanted,
          baseline = BigDecimal("300"),
          endOfProjectTarget = BigDecimal("3000"),
      )

      assertTableEquals(
          listOf(
              AutoCalculatedIndicatorTargetsRecord(
                  projectId = projectId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.SeedsCollected,
                  baseline = BigDecimal("100"),
                  endTarget = BigDecimal("1000"),
              ),
              AutoCalculatedIndicatorTargetsRecord(
                  projectId = projectId,
                  autoCalculatedIndicatorId = AutoCalculatedIndicator.TreesPlanted,
                  baseline = BigDecimal("300"),
                  endTarget = BigDecimal("3000"),
              ),
          )
      )
    }

    @Test
    fun `allows null values`() {
      store.updateAutoCalculatedIndicatorBaselineTarget(
          projectId = projectId,
          indicator = AutoCalculatedIndicator.TreesPlanted,
          baseline = null,
          endOfProjectTarget = null,
      )

      assertTableEquals(
          AutoCalculatedIndicatorTargetsRecord(
              projectId = projectId,
              autoCalculatedIndicatorId = AutoCalculatedIndicator.TreesPlanted,
          )
      )
    }

    @Test
    fun `requires permission to update project report targets`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Manager)

      assertThrows<AccessDeniedException> {
        store.updateAutoCalculatedIndicatorBaselineTarget(
            projectId = projectId,
            indicator = AutoCalculatedIndicator.TreesPlanted,
            baseline = BigDecimal("200"),
            endOfProjectTarget = BigDecimal("2000"),
        )
      }
    }
  }

  @Nested
  inner class FetchProjectIndicatorTargets {
    @Test
    fun `requires permission to read project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> { store.fetchProjectIndicatorTargets(projectId) }
    }

    @Test
    fun `returns yearly and baseline targets combined per indicator`() {
      val projectIndicatorId1 =
          insertProjectIndicator(
              description = "Test indicator 1",
              name = "Test Indicator 1",
              refId = "1.0",
          )
      insertReportProjectIndicatorTarget(year = 2024, target = 100)
      insertReportProjectIndicatorTarget(year = 2025, target = 150)
      insertProjectIndicatorBaselineTarget(baseline = 50, endTarget = 500)

      val projectIndicatorId2 =
          insertProjectIndicator(
              description = "Test indicator 2",
              name = "Test Indicator 2",
              refId = "2.0",
          )
      insertReportProjectIndicatorTarget(year = 2024, target = 200)

      assertEquals(
          listOf(
              ProjectIndicatorTargetsModel(
                  indicatorId = projectIndicatorId1,
                  baseline = BigDecimal("50"),
                  endOfProjectTarget = BigDecimal("500"),
                  yearlyTargets =
                      listOf(
                          YearlyIndicatorTargetModel(target = 100, year = 2024),
                          YearlyIndicatorTargetModel(target = 150, year = 2025),
                      ),
              ),
              ProjectIndicatorTargetsModel(
                  indicatorId = projectIndicatorId2,
                  baseline = null,
                  endOfProjectTarget = null,
                  yearlyTargets = listOf(YearlyIndicatorTargetModel(target = 200, year = 2024)),
              ),
          ),
          store.fetchProjectIndicatorTargets(projectId),
      )
    }

    @Test
    fun `returns indicator with only baseline and no yearly targets`() {
      val projectIndicatorId =
          insertProjectIndicator(
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
          )
      insertProjectIndicatorBaselineTarget(baseline = 10, endTarget = 100)

      assertEquals(
          listOf(
              ProjectIndicatorTargetsModel(
                  indicatorId = projectIndicatorId,
                  baseline = BigDecimal("10"),
                  endOfProjectTarget = BigDecimal("100"),
                  yearlyTargets = emptyList(),
              ),
          ),
          store.fetchProjectIndicatorTargets(projectId),
      )
    }

    @Test
    fun `does not return targets for other projects`() {
      insertProject()
      insertProjectIndicator(
          description = "Test indicator",
          name = "Test Indicator",
          refId = "1.0",
      )
      insertReportProjectIndicatorTarget(year = 2024, target = 999)
      insertProjectIndicatorBaselineTarget(baseline = 99, endTarget = 999)

      assertEquals(
          emptyList<ProjectIndicatorTargetsModel>(),
          store.fetchProjectIndicatorTargets(projectId),
      )
    }
  }

  @Nested
  inner class FetchCommonIndicatorTargets {
    @Test
    fun `requires permission to read project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> { store.fetchCommonIndicatorTargets(projectId) }
    }

    @Test
    fun `returns yearly and baseline targets combined per indicator`() {
      val commonIndicatorId1 =
          insertCommonIndicator(
              description = "Test indicator 1",
              name = "Test Indicator 1",
              refId = "1.0",
          )
      insertReportCommonIndicatorTarget(year = 2024, target = 300)
      insertReportCommonIndicatorTarget(year = 2025, target = 350)
      insertCommonIndicatorBaselineTarget(baseline = 75, endTarget = 750)

      val commonIndicatorId2 =
          insertCommonIndicator(
              description = "Test indicator 2",
              name = "Test Indicator 2",
              refId = "2.0",
          )
      insertReportCommonIndicatorTarget(year = 2024, target = 400)

      assertEquals(
          listOf(
              CommonIndicatorTargetsModel(
                  indicatorId = commonIndicatorId1,
                  baseline = BigDecimal("75"),
                  endOfProjectTarget = BigDecimal("750"),
                  yearlyTargets =
                      listOf(
                          YearlyIndicatorTargetModel(target = 300, year = 2024),
                          YearlyIndicatorTargetModel(target = 350, year = 2025),
                      ),
              ),
              CommonIndicatorTargetsModel(
                  indicatorId = commonIndicatorId2,
                  baseline = null,
                  endOfProjectTarget = null,
                  yearlyTargets = listOf(YearlyIndicatorTargetModel(target = 400, year = 2024)),
              ),
          ),
          store.fetchCommonIndicatorTargets(projectId),
      )
    }

    @Test
    fun `returns indicator with only baseline and no yearly targets`() {
      val commonIndicatorId =
          insertCommonIndicator(
              description = "Test indicator",
              name = "Test Indicator",
              refId = "1.0",
          )
      insertCommonIndicatorBaselineTarget(baseline = 20, endTarget = 200)

      assertEquals(
          listOf(
              CommonIndicatorTargetsModel(
                  indicatorId = commonIndicatorId,
                  baseline = BigDecimal("20"),
                  endOfProjectTarget = BigDecimal("200"),
                  yearlyTargets = emptyList(),
              ),
          ),
          store.fetchCommonIndicatorTargets(projectId),
      )
    }

    @Test
    fun `does not return targets for other projects`() {
      insertProject()
      insertCommonIndicator(
          description = "Test indicator",
          name = "Test Indicator",
          refId = "1.0",
      )
      insertReportCommonIndicatorTarget(year = 2024, target = 999)
      insertCommonIndicatorBaselineTarget(baseline = 99, endTarget = 999)

      assertEquals(
          emptyList<CommonIndicatorTargetsModel>(),
          store.fetchCommonIndicatorTargets(projectId),
      )
    }
  }

  @Nested
  inner class FetchAutoCalculatedIndicatorTargets {
    @Test
    fun `requires permission to read project reports`() {
      deleteUserGlobalRole(role = GlobalRole.AcceleratorAdmin)
      deleteOrganizationUser()
      insertOrganizationUser(role = Role.Contributor)

      assertThrows<AccessDeniedException> { store.fetchAutoCalculatedIndicatorTargets(projectId) }
    }

    @Test
    fun `returns yearly and baseline targets combined per indicator`() {
      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.TreesPlanted,
          year = 2024,
          target = 500,
      )
      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.TreesPlanted,
          year = 2025,
          target = 600,
      )
      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          year = 2024,
          target = 1000,
      )
      insertAutoCalculatedIndicatorBaselineTarget(
          indicator = AutoCalculatedIndicator.TreesPlanted,
          baseline = 200,
          endTarget = 2000,
      )

      assertEquals(
          listOf(
              AutoCalculatedIndicatorTargetsModel(
                  indicatorId = AutoCalculatedIndicator.SeedsCollected,
                  baseline = null,
                  endOfProjectTarget = null,
                  yearlyTargets = listOf(YearlyIndicatorTargetModel(target = 1000, year = 2024)),
              ),
              AutoCalculatedIndicatorTargetsModel(
                  indicatorId = AutoCalculatedIndicator.TreesPlanted,
                  baseline = BigDecimal("200"),
                  endOfProjectTarget = BigDecimal("2000"),
                  yearlyTargets =
                      listOf(
                          YearlyIndicatorTargetModel(target = 500, year = 2024),
                          YearlyIndicatorTargetModel(target = 600, year = 2025),
                      ),
              ),
          ),
          store.fetchAutoCalculatedIndicatorTargets(projectId),
      )
    }

    @Test
    fun `returns indicator with only baseline and no yearly targets`() {
      insertAutoCalculatedIndicatorBaselineTarget(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          baseline = 100,
          endTarget = 1000,
      )

      assertEquals(
          listOf(
              AutoCalculatedIndicatorTargetsModel(
                  indicatorId = AutoCalculatedIndicator.SeedsCollected,
                  baseline = BigDecimal("100"),
                  endOfProjectTarget = BigDecimal("1000"),
                  yearlyTargets = emptyList(),
              ),
          ),
          store.fetchAutoCalculatedIndicatorTargets(projectId),
      )
    }

    @Test
    fun `does not return targets for other projects`() {
      insertProject()
      insertReportAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.TreesPlanted,
          year = 2024,
          target = 999,
      )
      insertAutoCalculatedIndicatorBaselineTarget(
          indicator = AutoCalculatedIndicator.TreesPlanted,
          baseline = 99,
          endTarget = 999,
      )

      assertEquals(
          emptyList<AutoCalculatedIndicatorTargetsModel>(),
          store.fetchAutoCalculatedIndicatorTargets(projectId),
      )
    }
  }
}
