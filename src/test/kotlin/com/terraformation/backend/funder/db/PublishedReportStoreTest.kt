package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.accelerator.model.ReportPhotoModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.AutoCalculatedIndicator
import com.terraformation.backend.db.accelerator.IndicatorCategory
import com.terraformation.backend.db.accelerator.IndicatorLevel
import com.terraformation.backend.db.accelerator.ReportIndicatorStatus
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserType
import com.terraformation.backend.funder.model.PublishedReportMetricModel
import com.terraformation.backend.funder.model.PublishedReportModel
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
              component = IndicatorCategory.Climate,
              description = "Common Indicator Description 1",
              name = "Common Indicator 1",
              reference = "1.1.2",
              type = IndicatorLevel.Output,
          )

      val commonIndicatorId2 =
          insertCommonIndicator(
              component = IndicatorCategory.Community,
              description = "Common Indicator Description 2",
              name = "Common Indicator 2",
              reference = "1.1.1",
              type = IndicatorLevel.Outcome,
          )

      val projectIndicatorId1 =
          insertProjectIndicator(
              component = IndicatorCategory.Biodiversity,
              description = "Project Indicator Description 1",
              name = "Project Indicator 1",
              reference = "1.2.1",
              type = IndicatorLevel.Output,
              unit = "%",
          )

      val projectIndicatorId2 =
          insertProjectIndicator(
              component = IndicatorCategory.ProjectObjectives,
              description = "Project Indicator Description 2",
              name = "Project Indicator 2",
              reference = "1.2.11",
              type = IndicatorLevel.Outcome,
              unit = "USD",
          )

      val dealName = UUID.randomUUID().toString()
      insertProjectAcceleratorDetails(dealName = dealName)
      insertProjectReportConfig()

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
          indicator = AutoCalculatedIndicator.SurvivalRate,
          year = 2025,
          target = 6,
      )
      insertPublishedReportAutoCalculatedIndicator(
          reportId = reportId1,
          indicator = AutoCalculatedIndicator.SurvivalRate,
          value = 6,
          projectsComments = null,
          status = ReportIndicatorStatus.Achieved,
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
                  challenges = emptyList(),
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
                  commonIndicators = emptyList(),
                  startDate = LocalDate.of(2025, 4, 1),
                  autoCalculatedIndicators = emptyList(),
              ),
              PublishedReportModel(
                  achievements = listOf("achievement 1", "achievement 2"),
                  additionalComments = "additional comments",
                  challenges =
                      listOf(
                          ReportChallengeModel("challenge 1", "mitigation 1"),
                          ReportChallengeModel("challenge 2", "mitigation 2"),
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
                          PublishedReportMetricModel(
                              component = IndicatorCategory.Biodiversity,
                              description = "Project Indicator Description 1",
                              metricId = projectIndicatorId1,
                              name = "Project Indicator 1",
                              reference = "1.2.1",
                              status = ReportIndicatorStatus.OnTrack,
                              target = null,
                              type = IndicatorLevel.Output,
                              progressNotes = "progress notes 1",
                              projectsComments = null,
                              value = 40,
                              unit = "%",
                          ),
                          PublishedReportMetricModel(
                              component = IndicatorCategory.ProjectObjectives,
                              description = "Project Indicator Description 2",
                              metricId = projectIndicatorId2,
                              name = "Project Indicator 2",
                              reference = "1.2.11",
                              status = null,
                              target = null,
                              type = IndicatorLevel.Outcome,
                              progressNotes = null,
                              projectsComments = null,
                              value = null,
                              unit = "USD",
                          ),
                      ),
                  projectName = dealName,
                  publishedBy = user.userId,
                  publishedTime = Instant.EPOCH,
                  quarter = ReportQuarter.Q1,
                  reportId = reportId1,
                  commonIndicators =
                      listOf(
                          PublishedReportMetricModel(
                              component = IndicatorCategory.Community,
                              description = "Common Indicator Description 2",
                              metricId = commonIndicatorId2,
                              name = "Common Indicator 2",
                              reference = "1.1.1",
                              type = IndicatorLevel.Outcome,
                              target = 200,
                              value = 180,
                              progressNotes = "progress notes 2",
                              projectsComments = "Underperformance justification 2",
                              status = ReportIndicatorStatus.Unlikely,
                              unit = null,
                          ),
                          PublishedReportMetricModel(
                              component = IndicatorCategory.Climate,
                              description = "Common Indicator Description 1",
                              metricId = commonIndicatorId1,
                              name = "Common Indicator 1",
                              status = ReportIndicatorStatus.Achieved,
                              reference = "1.1.2",
                              target = 100,
                              type = IndicatorLevel.Output,
                              progressNotes = null,
                              projectsComments = null,
                              value = 120,
                              unit = null,
                          ),
                      ),
                  startDate = LocalDate.of(2025, 1, 1),
                  autoCalculatedIndicators =
                      listOf(
                          PublishedReportMetricModel(
                              component = AutoCalculatedIndicator.SurvivalRate.categoryId,
                              description = AutoCalculatedIndicator.SurvivalRate.description,
                              metricId = AutoCalculatedIndicator.SurvivalRate,
                              name = AutoCalculatedIndicator.SurvivalRate.jsonValue,
                              reference = AutoCalculatedIndicator.SurvivalRate.refId,
                              status = ReportIndicatorStatus.Achieved,
                              target = 6,
                              type = AutoCalculatedIndicator.SurvivalRate.levelId,
                              progressNotes = null,
                              projectsComments = null,
                              value = 6,
                              unit = "%",
                          ),
                      ),
              ),
          ),
          store.fetchPublishedReports(projectId),
      )
    }

    @Test
    fun `throws exception if no permission to read project reports`() {
      val projectId = insertProject()

      assertThrows<ProjectNotFoundException> { store.fetchPublishedReports(projectId) }
    }
  }
}
