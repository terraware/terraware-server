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
              description = "Common Indicator Description 1",
              name = "Common Indicator 1",
              refId = "1.1.2",
              level = IndicatorLevel.Output,
          )

      val commonIndicatorId2 =
          insertCommonIndicator(
              category = IndicatorCategory.Community,
              description = "Common Indicator Description 2",
              name = "Common Indicator 2",
              refId = "1.1.1",
              level = IndicatorLevel.Outcome,
          )

      val projectIndicatorId1 =
          insertProjectIndicator(
              category = IndicatorCategory.Biodiversity,
              description = "Project Indicator Description 1",
              name = "Project Indicator 1",
              refId = "1.2.1",
              level = IndicatorLevel.Output,
              unit = "%",
          )

      val projectIndicatorId2 =
          insertProjectIndicator(
              category = IndicatorCategory.ProjectObjectives,
              description = "Project Indicator Description 2",
              name = "Project Indicator 2",
              refId = "1.2.11",
              level = IndicatorLevel.Outcome,
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
          target = BigDecimal(100),
      )
      insertPublishedReportCommonIndicator(
          reportId = reportId1,
          indicatorId = commonIndicatorId1,
          value = BigDecimal(120),
          projectsComments = null,
          status = ReportIndicatorStatus.Achieved,
      )

      insertPublishedCommonIndicatorTarget(
          commonIndicatorId = commonIndicatorId2,
          year = 2025,
          target = BigDecimal(200),
      )
      insertPublishedReportCommonIndicator(
          reportId = reportId1,
          indicatorId = commonIndicatorId2,
          value = BigDecimal(180),
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
          value = BigDecimal(40),
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
          target = BigDecimal(6),
      )
      insertPublishedReportAutoCalculatedIndicator(
          reportId = reportId1,
          indicator = AutoCalculatedIndicator.SurvivalRate,
          value = BigDecimal(6),
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
                          PublishedReportIndicatorModel(
                              category = IndicatorCategory.Biodiversity,
                              description = "Project Indicator Description 1",
                              indicatorId = projectIndicatorId1,
                              level = IndicatorLevel.Output,
                              name = "Project Indicator 1",
                              refId = "1.2.1",
                              status = ReportIndicatorStatus.OnTrack,
                              target = null,
                              progressNotes = "progress notes 1",
                              projectsComments = null,
                              unit = "%",
                              value = 40,
                          ),
                          PublishedReportIndicatorModel(
                              category = IndicatorCategory.ProjectObjectives,
                              description = "Project Indicator Description 2",
                              indicatorId = projectIndicatorId2,
                              level = IndicatorLevel.Outcome,
                              name = "Project Indicator 2",
                              refId = "1.2.11",
                              status = null,
                              target = null,
                              progressNotes = null,
                              projectsComments = null,
                              unit = "USD",
                              value = null,
                          ),
                      ),
                  projectName = dealName,
                  publishedBy = user.userId,
                  publishedTime = Instant.EPOCH,
                  quarter = ReportQuarter.Q1,
                  reportId = reportId1,
                  commonIndicators =
                      listOf(
                          PublishedReportIndicatorModel(
                              category = IndicatorCategory.Community,
                              description = "Common Indicator Description 2",
                              indicatorId = commonIndicatorId2,
                              level = IndicatorLevel.Outcome,
                              name = "Common Indicator 2",
                              refId = "1.1.1",
                              target = 200,
                              value = 180,
                              progressNotes = "progress notes 2",
                              projectsComments = "Underperformance justification 2",
                              status = ReportIndicatorStatus.Unlikely,
                              unit = null,
                          ),
                          PublishedReportIndicatorModel(
                              category = IndicatorCategory.Climate,
                              description = "Common Indicator Description 1",
                              indicatorId = commonIndicatorId1,
                              level = IndicatorLevel.Output,
                              name = "Common Indicator 1",
                              status = ReportIndicatorStatus.Achieved,
                              refId = "1.1.2",
                              target = 100,
                              progressNotes = null,
                              projectsComments = null,
                              unit = null,
                              value = 120,
                          ),
                      ),
                  startDate = LocalDate.of(2025, 1, 1),
                  autoCalculatedIndicators =
                      listOf(
                          PublishedReportIndicatorModel(
                              category = AutoCalculatedIndicator.SurvivalRate.categoryId,
                              description = AutoCalculatedIndicator.SurvivalRate.description,
                              indicatorId = AutoCalculatedIndicator.SurvivalRate,
                              level = AutoCalculatedIndicator.SurvivalRate.levelId,
                              name = AutoCalculatedIndicator.SurvivalRate.jsonValue,
                              refId = AutoCalculatedIndicator.SurvivalRate.refId,
                              status = ReportIndicatorStatus.Achieved,
                              target = 6,
                              progressNotes = null,
                              projectsComments = null,
                              unit = "%",
                              value = 6,
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
