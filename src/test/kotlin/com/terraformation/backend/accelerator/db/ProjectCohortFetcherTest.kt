package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.model.ProjectCohortData
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProjectCohortFetcherTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val fetcher: ProjectCohortFetcher by lazy { ProjectCohortFetcher(dslContext) }

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    every { user.canReadProject(any()) } returns true

    organizationId = insertOrganization()
  }

  @Nested
  inner class FetchCohortData {
    @Test
    fun `fetches project's cohort data`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)

      val expected =
          ProjectCohortData(cohortId = cohortId, cohortPhase = CohortPhase.Phase0DueDiligence)
      val actual = fetcher.fetchCohortData(projectId)

      assertEquals(expected, actual)
    }

    @Test
    fun `uses cohort data even if project has an application`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)
      insertApplication()

      val expected =
          ProjectCohortData(cohortId = cohortId, cohortPhase = CohortPhase.Phase0DueDiligence)
      val actual = fetcher.fetchCohortData(projectId)

      assertEquals(expected, actual)
    }

    @Test
    fun `returns correct cohort phase for each application state`() {
      val projectId = insertProject()
      insertApplication()

      val phasesByState =
          ApplicationStatus.entries.associateWith { state ->
            dslContext.update(APPLICATIONS).set(APPLICATIONS.APPLICATION_STATUS_ID, state).execute()

            val cohortData = fetcher.fetchCohortData(projectId)

            assertNotNull(cohortData, "Cohort data")
            assertNull(cohortData!!.cohortId, "Cohort ID")

            cohortData.cohortPhase
          }

      assertEquals(
          mapOf(
              ApplicationStatus.Accepted to CohortPhase.Application,
              ApplicationStatus.CarbonEligible to CohortPhase.Application,
              ApplicationStatus.FailedPreScreen to CohortPhase.PreScreen,
              ApplicationStatus.IssueActive to CohortPhase.Application,
              ApplicationStatus.IssuePending to CohortPhase.Application,
              ApplicationStatus.IssueResolved to CohortPhase.Application,
              ApplicationStatus.NeedsFollowUp to CohortPhase.Application,
              ApplicationStatus.NotAccepted to CohortPhase.Application,
              ApplicationStatus.NotSubmitted to CohortPhase.PreScreen,
              ApplicationStatus.PassedPreScreen to CohortPhase.PreScreen,
              ApplicationStatus.PLReview to CohortPhase.Application,
              ApplicationStatus.PreCheck to CohortPhase.Application,
              ApplicationStatus.ReadyForReview to CohortPhase.Application,
              ApplicationStatus.Submitted to CohortPhase.Application,
          ),
          phasesByState)
    }

    @Test
    fun `throws exception if no permission to read project`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)

      every { user.canReadProject(projectId) } returns false

      assertThrows<ProjectNotFoundException> { fetcher.fetchCohortData(projectId) }
    }

    @Test
    fun `returns null if the project is not associated to a participant which is associated to a cohort`() {
      val participantId = insertParticipant()
      val projectId = insertProject(participantId = participantId)

      val actual = fetcher.fetchCohortData(projectId)
      assertNull(actual)
    }

    @Test
    fun `returns null if the project is not associated to a participant and has no application`() {
      val projectId = insertProject()
      val actual = fetcher.fetchCohortData(projectId)
      assertNull(actual)
    }
  }
}
