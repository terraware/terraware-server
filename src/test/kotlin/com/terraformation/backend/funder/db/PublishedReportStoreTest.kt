package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.accelerator.model.ReportPhotoModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorClass
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ReportIndicatorStatus
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.funder.model.PublishedReportIndicatorModel
import com.terraformation.backend.funder.model.PublishedReportModel
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PublishedReportStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser
  override val defaultUserType: UserType
    get() = UserType.Funder

  private val store: PublishedReportStore by lazy { PublishedReportStore(dslContext) }

  private lateinit var projectId: ProjectId

  @BeforeEach
  fun setUp() {
    insertFundingEntity()
    insertFundingEntityUser()
    insertOrganization()
    projectId = insertProject()
  }

  @Nested
  inner class FetchPublishedReports {
    @Test
    fun `returns all published report data`() {
      insertFundingEntityProject()

      val commonIndicatorId1 =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              classId = IndicatorClass.Cumulative,
              description = "Common Indicator Description 1",
              name = "Common Indicator 1",
              refId = "1.1.2",
              level = IndicatorLevel.Output,
          )

      val commonIndicatorId2 =
          insertCommonIndicator(
              category = IndicatorCategory.Community,
              classId = IndicatorClass.Cumulative,
              description = "Common Indicator Description 2",
              name = "Common Indicator 2",
              refId = "1.1.1",
              level = IndicatorLevel.Outcome,
          )

      val projectIndicatorId1 =
          insertProjectIndicator(
              category = IndicatorCategory.Biodiversity,
              classId = IndicatorClass.Cumulative,
              description = "Project Indicator Description 1",
              name = "Project Indicator 1",
              refId = "1.2.1",
              level = IndicatorLevel.Output,
              unit = "%",
          )

      val projectIndicatorId2 =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              classId = IndicatorClass.Cumulative,
              description = "Project Indicator Description 2",
              name = "Project Indicator 2",
              refId = "1.2.11",
              level = IndicatorLevel.Outcome,
              unit = "USD",
          )

      val dealName = UUID.randomUUID().toString()
      insertProjectAcceleratorDetails(dealName = dealName)
      insertProjectReportConfig()

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

      // oldReport2
      insertReport(endDate = LocalDate.of(2024, 12, 31))
      insertReportProjectIndicator(indicatorId = projectIndicatorId1, value = 100)
      insertReportProjectIndicator(indicatorId = projectIndicatorId2, value = 101)
      insertReportCommonIndicator(indicatorId = commonIndicatorId1, value = 200)
      insertReportCommonIndicator(indicatorId = commonIndicatorId2, value = 201)
      insertReportAutoCalculatedIndicator(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          systemValue = 29,
          overrideValue = 31,
      )

      val reportId1 =
          insertReport(
              startDate = LocalDate.of(2025, 1, 1),
              endDate = LocalDate.of(2025, 3, 31),
              quarter = ReportQuarter.Q1,
          )
      insertPublishedReport(
          startDate = LocalDate.of(2025, 1, 1),
          endDate = LocalDate.of(2025, 3, 31),
          quarter = ReportQuarter.Q1,
          highlights = "highlights",
          additionalComments = "additional comments",
          financialSummaries = "financial summaries",
      )
      insertPublishedReportAchievement(achievement = "achievement 2", position = 2)
      insertPublishedReportAchievement(achievement = "achievement 1", position = 1)
      insertPublishedReportChallenge(
          challenge = "challenge 2",
          mitigationPlan = "mitigation 2",
          position = 2,
      )
      insertPublishedReportChallenge(
          challenge = "challenge 1",
          mitigationPlan = "mitigation 1",
          position = 1,
      )

      val fileId1 = insertFile()
      insertReportPhoto(caption = "photo caption 1")
      insertPublishedReportPhoto(caption = "photo caption 1")

      val fileId2 = insertFile()
      insertReportPhoto(caption = "photo caption 2")
      insertPublishedReportPhoto(caption = "photo caption 2")

      insertPublishedCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId1,
          year = 2025,
          target = 100,
      )
      insertPublishedReportCommonIndicator(
          reportId = reportId1,
          indicatorId = commonIndicatorId1,
          value = 120,
          projectsComments = null,
          status = ReportIndicatorStatus.Achieved,
      )

      insertPublishedCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId2,
          year = 2025,
          target = 200,
      )
      insertPublishedReportCommonIndicator(
          reportId = reportId1,
          indicatorId = commonIndicatorId2,
          value = 180,
          progressNotes = "progress notes 2",
          projectsComments = "Underperformance justification 2",
          status = ReportIndicatorStatus.Unlikely,
      )

      insertPublishedProjectIndicatorTarget(
          projectIndicatorId = projectIndicatorId1,
          year = 2025,
          target = null,
      )
      insertPublishedReportProjectIndicator(
          reportId = reportId1,
          indicatorId = projectIndicatorId1,
          value = 40,
          progressNotes = "progress notes 1",
          projectsComments = null,
          status = ReportIndicatorStatus.OnTrack,
      )

      insertPublishedProjectIndicatorTarget(
          projectIndicatorId = projectIndicatorId2,
          year = 2025,
          target = null,
      )
      insertPublishedReportProjectIndicator(
          reportId = reportId1,
          indicatorId = projectIndicatorId2,
          value = null,
          projectsComments = null,
          status = null,
      )

      insertPublishedAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          year = 2025,
          target = 6,
      )
      insertPublishedReportAutoCalculatedIndicator(
          reportId = reportId1,
          indicator = AutoCalculatedIndicator.SeedsCollected,
          value = 6,
          projectsComments = null,
          status = ReportIndicatorStatus.OffTrack,
      )

      insertPublishedCommonIndicatorBaseline(
          commonIndicatorId = commonIndicatorId2,
          baseline = 200,
          endTarget = 250,
      )

      insertPublishedProjectIndicatorBaseline(
          projectIndicatorId = projectIndicatorId2,
          baseline = 210,
          endTarget = 220,
      )

      insertPublishedAutoCalculatedIndicatorBaseline(
          indicator = AutoCalculatedIndicator.SeedsCollected,
          baseline = 300,
          endTarget = 400,
      )

      val reportId2 =
          insertReport(
              startDate = LocalDate.of(2025, 4, 1),
              endDate = LocalDate.of(2025, 6, 30),
              quarter = ReportQuarter.Q2,
          )
      insertPublishedReport(
          startDate = LocalDate.of(2025, 4, 1),
          endDate = LocalDate.of(2025, 6, 30),
          quarter = ReportQuarter.Q2,
          publishedTime = Instant.ofEpochSecond(1),
      )

      assertEquals(
          listOf(
              PublishedReportModel(
                  achievements = emptyList(),
                  additionalComments = null,
                  autoCalculatedIndicators = emptyList(),
                  challenges = emptyList(),
                  commonIndicators = emptyList(),
                  endDate = LocalDate.of(2025, 6, 30),
                  financialSummaries = null,
                  highlights = null,
                  photos = emptyList(),
                  projectId = projectId,
                  projectIndicators = emptyList(),
                  projectName = dealName,
                  publishedBy = user.userId,
                  publishedTime = Instant.ofEpochSecond(1),
                  quarter = ReportQuarter.Q2,
                  reportId = reportId2,
                  startDate = LocalDate.of(2025, 4, 1),
              ),
              PublishedReportModel(
                  achievements = listOf("achievement 1", "achievement 2"),
                  additionalComments = "additional comments",
                  autoCalculatedIndicators =
                      listOf(
                          PublishedReportIndicatorModel(
                              baseline = BigDecimal.valueOf(300),
                              classId = IndicatorClass.Cumulative,
                              category = AutoCalculatedIndicator.SeedsCollected.categoryId,
                              description = AutoCalculatedIndicator.SeedsCollected.description,
                              endOfProjectTarget = BigDecimal.valueOf(400),
                              indicatorId = AutoCalculatedIndicator.SeedsCollected,
                              level = AutoCalculatedIndicator.SeedsCollected.levelId,
                              name = AutoCalculatedIndicator.SeedsCollected.jsonValue,
                              refId = AutoCalculatedIndicator.SeedsCollected.refId,
                              previousYearCumulativeTotal = BigDecimal("61"),
                              progressNotes = null,
                              projectsComments = null,
                              status = ReportIndicatorStatus.OffTrack,
                              target = 6,
                              unit = "Seeds",
                              value = 6,
                          ),
                      ),
                  challenges =
                      listOf(
                          ReportChallengeModel("challenge 1", "mitigation 1"),
                          ReportChallengeModel("challenge 2", "mitigation 2"),
                      ),
                  commonIndicators =
                      listOf(
                          PublishedReportIndicatorModel(
                              baseline = BigDecimal.valueOf(200),
                              category = IndicatorCategory.Community,
                              classId = IndicatorClass.Cumulative,
                              description = "Common Indicator Description 2",
                              endOfProjectTarget = BigDecimal.valueOf(250),
                              indicatorId = commonIndicatorId2,
                              level = IndicatorLevel.Outcome,
                              name = "Common Indicator 2",
                              previousYearCumulativeTotal = BigDecimal("222"),
                              progressNotes = "progress notes 2",
                              projectsComments = "Underperformance justification 2",
                              refId = "1.1.1",
                              status = ReportIndicatorStatus.Unlikely,
                              target = 200,
                              unit = null,
                              value = 180,
                          ),
                          PublishedReportIndicatorModel(
                              category = IndicatorCategory.Climate,
                              classId = IndicatorClass.Cumulative,
                              description = "Common Indicator Description 1",
                              endOfProjectTarget = null,
                              indicatorId = commonIndicatorId1,
                              level = IndicatorLevel.Output,
                              name = "Common Indicator 1",
                              previousYearCumulativeTotal = BigDecimal("220"),
                              progressNotes = null,
                              projectsComments = null,
                              refId = "1.1.2",
                              status = ReportIndicatorStatus.Achieved,
                              target = 100,
                              unit = null,
                              value = 120,
                          ),
                      ),
                  endDate = LocalDate.of(2025, 3, 31),
                  financialSummaries = "financial summaries",
                  highlights = "highlights",
                  photos =
                      listOf(
                          ReportPhotoModel(fileId = fileId1, caption = "photo caption 1"),
                          ReportPhotoModel(fileId = fileId2, caption = "photo caption 2"),
                      ),
                  projectId = projectId,
                  projectIndicators =
                      listOf(
                          PublishedReportIndicatorModel(
                              baseline = null,
                              category = IndicatorCategory.Biodiversity,
                              classId = IndicatorClass.Cumulative,
                              description = "Project Indicator Description 1",
                              endOfProjectTarget = null,
                              indicatorId = projectIndicatorId1,
                              level = IndicatorLevel.Output,
                              name = "Project Indicator 1",
                              previousYearCumulativeTotal = BigDecimal("110"),
                              progressNotes = "progress notes 1",
                              projectsComments = null,
                              refId = "1.2.1",
                              status = ReportIndicatorStatus.OnTrack,
                              target = null,
                              unit = "%",
                              value = 40,
                          ),
                          PublishedReportIndicatorModel(
                              baseline = BigDecimal.valueOf(210),
                              category = IndicatorCategory.ProjectObjectives,
                              classId = IndicatorClass.Cumulative,
                              description = "Project Indicator Description 2",
                              endOfProjectTarget = BigDecimal.valueOf(220),
                              indicatorId = projectIndicatorId2,
                              level = IndicatorLevel.Outcome,
                              name = "Project Indicator 2",
                              previousYearCumulativeTotal = BigDecimal("112"),
                              progressNotes = null,
                              projectsComments = null,
                              refId = "1.2.11",
                              status = null,
                              target = null,
                              unit = "USD",
                              value = null,
                          ),
                      ),
                  projectName = dealName,
                  publishedBy = user.userId,
                  publishedTime = Instant.EPOCH,
                  quarter = ReportQuarter.Q1,
                  reportId = reportId1,
                  startDate = LocalDate.of(2025, 1, 1),
              ),
          ),
          store.fetchPublishedReports(projectId),
      )
    }

    @Test
    fun `only includes project indicator targets for the report year and project`() {
      insertFundingEntityProject()
      insertProjectReportConfig()

      val projectIndicatorId =
          insertProjectIndicator(
              category = IndicatorCategory.Biodiversity,
              classId = IndicatorClass.Level,
              description = "Project Indicator Description",
              name = "Project Indicator",
              refId = "1.2.1",
              level = IndicatorLevel.Output,
          )

      val reportId =
          insertReport(
              startDate = LocalDate.of(2024, 1, 1),
              endDate = LocalDate.of(2024, 12, 31),
          )
      insertPublishedReport(
          reportId = reportId,
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 12, 31),
      )
      insertPublishedReportProjectIndicator(
          reportId = reportId,
          indicatorId = projectIndicatorId,
          value = 10,
          status = ReportIndicatorStatus.OnTrack,
      )

      // Target for the report's year (2024) — should be included
      insertPublishedProjectIndicatorTarget(
          projectIndicatorId = projectIndicatorId,
          year = 2024,
          target = 100,
      )
      // Target for a different year — should not cause a duplicate row
      insertPublishedProjectIndicatorTarget(
          projectIndicatorId = projectIndicatorId,
          year = 2025,
          target = 999,
      )
      // Target for a different project in the same year — should not cause a duplicate row
      val otherProjectId = insertProject()
      insertPublishedProjectIndicatorTarget(
          projectId = otherProjectId,
          projectIndicatorId = projectIndicatorId,
          year = 2024,
          target = 888,
      )

      val reports = store.fetchPublishedReports(projectId)
      val expected =
          PublishedReportIndicatorModel(
              category = IndicatorCategory.Biodiversity,
              classId = IndicatorClass.Level,
              description = "Project Indicator Description",
              indicatorId = projectIndicatorId,
              level = IndicatorLevel.Output,
              name = "Project Indicator",
              progressNotes = null,
              projectsComments = null,
              refId = "1.2.1",
              status = ReportIndicatorStatus.OnTrack,
              target = 100,
              unit = null,
              value = 10,
          )
      assertEquals(
          listOf(expected),
          reports.single().projectIndicators,
          "Other years and projects don't affect project indicators",
      )
    }

    @Test
    fun `only includes common indicator targets for the report year and project`() {
      insertFundingEntityProject()
      insertProjectReportConfig()

      val commonIndicatorId =
          insertCommonIndicator(
              category = IndicatorCategory.Climate,
              classId = IndicatorClass.Cumulative,
              description = "Common Indicator Description",
              name = "Common Indicator",
              refId = "1.1.1",
              level = IndicatorLevel.Output,
          )

      val reportId =
          insertReport(
              startDate = LocalDate.of(2024, 1, 1),
              endDate = LocalDate.of(2024, 12, 31),
          )
      insertPublishedReport(
          reportId = reportId,
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 12, 31),
      )
      insertPublishedReportCommonIndicator(
          reportId = reportId,
          indicatorId = commonIndicatorId,
          value = 20,
          status = ReportIndicatorStatus.Achieved,
      )

      // Target for the report's year (2024) — should be included
      insertPublishedCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId,
          year = 2024,
          target = 200,
      )
      // Target for a different year — should not cause a duplicate row
      insertPublishedCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId,
          year = 2025,
          target = 999,
      )
      // Target for a different project in the same year — should not cause a duplicate row
      val otherProjectId = insertProject()
      insertPublishedCommonIndicatorTarget(
          projectId = otherProjectId,
          commonIndicatorId = commonIndicatorId,
          year = 2024,
          target = 888,
      )

      val reports = store.fetchPublishedReports(projectId)
      val expected =
          PublishedReportIndicatorModel(
              category = IndicatorCategory.Climate,
              classId = IndicatorClass.Cumulative,
              description = "Common Indicator Description",
              indicatorId = commonIndicatorId,
              level = IndicatorLevel.Output,
              name = "Common Indicator",
              progressNotes = null,
              projectsComments = null,
              refId = "1.1.1",
              status = ReportIndicatorStatus.Achieved,
              target = 200,
              unit = null,
              value = 20,
          )
      assertEquals(
          listOf(expected),
          reports.single().commonIndicators,
          "Other years and projects don't affect common indicators",
      )
    }

    @Test
    fun `only includes auto-calculated indicator targets for the report year and project`() {
      insertFundingEntityProject()
      insertProjectReportConfig()

      val reportId =
          insertReport(
              startDate = LocalDate.of(2024, 1, 1),
              endDate = LocalDate.of(2024, 12, 31),
          )
      insertPublishedReport(
          reportId = reportId,
          startDate = LocalDate.of(2024, 1, 1),
          endDate = LocalDate.of(2024, 12, 31),
      )
      insertPublishedReportAutoCalculatedIndicator(
          reportId = reportId,
          indicator = AutoCalculatedIndicator.SurvivalRate,
          value = 5,
          status = ReportIndicatorStatus.OnTrack,
      )

      // Target for the report's year (2024) — should be included
      insertPublishedAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SurvivalRate,
          year = 2024,
          target = 300,
      )
      // Target for a different year — should not cause a duplicate row
      insertPublishedAutoCalculatedIndicatorTarget(
          indicator = AutoCalculatedIndicator.SurvivalRate,
          year = 2025,
          target = 999,
      )
      // Target for a different project in the same year — should not cause a duplicate row
      val otherProjectId = insertProject()
      insertPublishedAutoCalculatedIndicatorTarget(
          projectId = otherProjectId,
          indicator = AutoCalculatedIndicator.SurvivalRate,
          year = 2024,
          target = 888,
      )

      val reports = store.fetchPublishedReports(projectId)
      val expected =
          PublishedReportIndicatorModel(
              category = AutoCalculatedIndicator.SurvivalRate.categoryId,
              classId = IndicatorClass.Level,
              description = AutoCalculatedIndicator.SurvivalRate.description,
              indicatorId = AutoCalculatedIndicator.SurvivalRate,
              level = AutoCalculatedIndicator.SurvivalRate.levelId,
              name = AutoCalculatedIndicator.SurvivalRate.jsonValue,
              progressNotes = null,
              projectsComments = null,
              refId = AutoCalculatedIndicator.SurvivalRate.refId,
              status = ReportIndicatorStatus.OnTrack,
              target = 300,
              unit = "%",
              value = 5,
          )
      assertEquals(
          listOf(expected),
          reports.single().autoCalculatedIndicators,
          "Other years and projects don't affect auto-calculated indicators",
      )
    }

    @Test
    fun `throws exception if no permission to read project reports`() {
      val projectId = insertProject()

      assertThrows<ProjectNotFoundException> { store.fetchPublishedReports(projectId) }
    }
  }
}
