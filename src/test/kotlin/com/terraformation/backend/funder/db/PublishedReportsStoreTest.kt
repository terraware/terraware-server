package com.terraformation.backend.funder.db

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.accelerator.model.ReportChallengeModel
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.ReportFrequency
import com.terraformation.backend.db.accelerator.ReportQuarter
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserType
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
                  projectName = dealName,
                  publishedBy = user.userId,
                  publishedTime = Instant.ofEpochSecond(1),
                  quarter = ReportQuarter.Q2,
                  reportId = reportId2,
                  startDate = LocalDate.of(2025, 4, 1),
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
                  projectName = dealName,
                  publishedBy = user.userId,
                  publishedTime = Instant.EPOCH,
                  quarter = ReportQuarter.Q1,
                  reportId = reportId1,
                  startDate = LocalDate.of(2025, 1, 1),
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
