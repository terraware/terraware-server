package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.MetricComponent
import com.terraformation.backend.db.accelerator.MetricType
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportMetricStatus
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.accelerator.SystemMetric
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

class PublishedReportsStoreTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser
  override val defaultUserType: UserType
    get() = UserType.Funder

  private val store: PublishedReportsStore by lazy { PublishedReportsStore(dslContext) }

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

      val standardMetricId1 =
          insertStandardMetric(
              component = MetricComponent.Climate,
              description = "Standard Metric Description 1",
              name = "Standard Metric 1",
              reference = "1.1.2",
              type = MetricType.Output,
          )

      val standardMetricId2 =
          insertStandardMetric(
              component = MetricComponent.Community,
              description = "Standard Metric Description 2",
              name = "Standard Metric 2",
              reference = "1.1.1",
              type = MetricType.Outcome,
          )

      val projectMetricId1 =
          insertProjectMetric(
              component = MetricComponent.Biodiversity,
              description = "Project Metric Description 1",
              name = "Project Metric 1",
              reference = "1.2.1",
              type = MetricType.Output,
          )

      val projectMetricId2 =
          insertProjectMetric(
              component = MetricComponent.ProjectObjectives,
              description = "Project Metric Description 2",
              name = "Project Metric 2",
              reference = "1.2.11",
              type = MetricType.Outcome,
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
      )
      insertPublishedReportAchievement(achievement = "achievement 2", position = 2)
      insertPublishedReportAchievement(achievement = "achievement 1", position = 1)
      insertPublishedReportChallenge(
          challenge = "challenge 2", mitigationPlan = "mitigation 2", position = 2)
      insertPublishedReportChallenge(
          challenge = "challenge 1", mitigationPlan = "mitigation 1", position = 1)

      insertPublishedReportStandardMetric(
          reportId = reportId1,
          metricId = standardMetricId1,
          target = 100,
          value = 120,
          underperformanceJustification = null,
          status = ReportMetricStatus.Achieved,
      )

      insertPublishedReportStandardMetric(
          reportId = reportId1,
          metricId = standardMetricId2,
          target = 200,
          value = 180,
          progressNotes = "progress notes 2",
          underperformanceJustification = "Underperformance justification 2",
          status = ReportMetricStatus.Unlikely,
      )

      insertPublishedReportProjectMetric(
          reportId = reportId1,
          metricId = projectMetricId1,
          target = null,
          value = 40,
          progressNotes = "progress notes 1",
          underperformanceJustification = null,
          status = ReportMetricStatus.OnTrack,
      )

      insertPublishedReportProjectMetric(
          reportId = reportId1,
          metricId = projectMetricId2,
          target = null,
          value = null,
          underperformanceJustification = null,
          status = null,
      )

      insertPublishedReportSystemMetric(
          reportId = reportId1,
          metric = SystemMetric.MortalityRate,
          target = 0,
          value = 5,
          underperformanceJustification = "Some plants had died.",
          status = ReportMetricStatus.Unlikely,
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
                  challenges = emptyList(),
                  endDate = LocalDate.of(2025, 6, 30),
                  frequency = ReportFrequency.Quarterly,
                  highlights = null,
                  projectId = projectId,
                  projectMetrics = emptyList(),
                  projectName = dealName,
                  publishedBy = user.userId,
                  publishedTime = Instant.ofEpochSecond(1),
                  quarter = ReportQuarter.Q2,
                  reportId = reportId2,
                  standardMetrics = emptyList(),
                  startDate = LocalDate.of(2025, 4, 1),
                  systemMetrics = emptyList(),
              ),
              PublishedReportModel(
                  achievements = listOf("achievement 1", "achievement 2"),
                  challenges =
                      listOf(
                          ReportChallengeModel("challenge 1", "mitigation 1"),
                          ReportChallengeModel("challenge 2", "mitigation 2"),
                      ),
                  endDate = LocalDate.of(2025, 3, 31),
                  frequency = ReportFrequency.Quarterly,
                  highlights = "highlights",
                  projectId = projectId,
                  projectMetrics =
                      listOf(
                          PublishedReportMetricModel(
                              component = MetricComponent.Biodiversity,
                              description = "Project Metric Description 1",
                              metricId = projectMetricId1,
                              name = "Project Metric 1",
                              reference = "1.2.1",
                              status = ReportMetricStatus.OnTrack,
                              target = null,
                              type = MetricType.Output,
                              progressNotes = "progress notes 1",
                              underperformanceJustification = null,
                              value = 40,
                          ),
                          PublishedReportMetricModel(
                              component = MetricComponent.ProjectObjectives,
                              description = "Project Metric Description 2",
                              metricId = projectMetricId2,
                              name = "Project Metric 2",
                              reference = "1.2.11",
                              status = null,
                              target = null,
                              type = MetricType.Outcome,
                              progressNotes = null,
                              underperformanceJustification = null,
                              value = null,
                          ),
                      ),
                  projectName = dealName,
                  publishedBy = user.userId,
                  publishedTime = Instant.EPOCH,
                  quarter = ReportQuarter.Q1,
                  reportId = reportId1,
                  standardMetrics =
                      listOf(
                          PublishedReportMetricModel(
                              component = MetricComponent.Community,
                              description = "Standard Metric Description 2",
                              metricId = standardMetricId2,
                              name = "Standard Metric 2",
                              reference = "1.1.1",
                              type = MetricType.Outcome,
                              target = 200,
                              value = 180,
                              progressNotes = "progress notes 2",
                              underperformanceJustification = "Underperformance justification 2",
                              status = ReportMetricStatus.Unlikely,
                          ),
                          PublishedReportMetricModel(
                              component = MetricComponent.Climate,
                              description = "Standard Metric Description 1",
                              metricId = standardMetricId1,
                              name = "Standard Metric 1",
                              status = ReportMetricStatus.Achieved,
                              reference = "1.1.2",
                              target = 100,
                              type = MetricType.Output,
                              progressNotes = null,
                              underperformanceJustification = null,
                              value = 120,
                          ),
                      ),
                  startDate = LocalDate.of(2025, 1, 1),
                  systemMetrics =
                      listOf(
                          PublishedReportMetricModel(
                              component = SystemMetric.MortalityRate.componentId,
                              description = SystemMetric.MortalityRate.description,
                              metricId = SystemMetric.MortalityRate,
                              name = SystemMetric.MortalityRate.jsonValue,
                              reference = SystemMetric.MortalityRate.reference,
                              status = ReportMetricStatus.Unlikely,
                              target = 0,
                              type = SystemMetric.MortalityRate.typeId,
                              progressNotes = null,
                              underperformanceJustification = "Some plants had died.",
                              value = 5,
                          )),
              )),
          store.fetchPublishedReports(projectId))
    }

    @Test
    fun `throws exception if no permission to read project reports`() {
      val projectId = insertProject()

      assertThrows<ProjectNotFoundException> { store.fetchPublishedReports(projectId) }
    }
  }
}
