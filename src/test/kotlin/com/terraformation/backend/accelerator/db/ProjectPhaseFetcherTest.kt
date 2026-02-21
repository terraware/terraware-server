package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.AcceleratorPhase
import com.terraformation.backend.db.accelerator.ApplicationStatus
import com.terraformation.backend.db.accelerator.tables.references.APPLICATIONS
import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ProjectPhaseFetcherTest : DatabaseTest(), RunsAsUser {
  override val user: TerrawareUser = mockUser()

  private val fetcher: ProjectPhaseFetcher by lazy { ProjectPhaseFetcher(dslContext) }

  private lateinit var organizationId: OrganizationId

  @BeforeEach
  fun setUp() {
    every { user.canReadProject(any()) } returns true

    organizationId = insertOrganization()
  }

  @Nested
  inner class GetProjectPhase {
    @Test
    fun `fetches project's phase`() {
      val projectId = insertProject(phase = AcceleratorPhase.Phase0DueDiligence)

      assertEquals(AcceleratorPhase.Phase0DueDiligence, fetcher.getProjectPhase(projectId))
    }

    @Test
    fun `uses project phase even if project has an application`() {
      val projectId = insertProject(phase = AcceleratorPhase.Phase0DueDiligence)
      insertApplication()

      assertEquals(AcceleratorPhase.Phase0DueDiligence, fetcher.getProjectPhase(projectId))
    }

    @Test
    fun `returns correct phase for each application state`() {
      val projectId = insertProject()
      insertApplication()

      val phasesByState =
          ApplicationStatus.entries.associateWith { state ->
            dslContext.update(APPLICATIONS).set(APPLICATIONS.APPLICATION_STATUS_ID, state).execute()
            fetcher.getProjectPhase(projectId)
          }

      assertEquals(
          mapOf(
              ApplicationStatus.Accepted to AcceleratorPhase.Application,
              ApplicationStatus.CarbonAssessment to AcceleratorPhase.Application,
              ApplicationStatus.ExpertReview to AcceleratorPhase.Application,
              ApplicationStatus.FailedPreScreen to AcceleratorPhase.PreScreen,
              ApplicationStatus.GISAssessment to AcceleratorPhase.Application,
              ApplicationStatus.IssueActive to AcceleratorPhase.Application,
              ApplicationStatus.IssueReassessment to AcceleratorPhase.Application,
              ApplicationStatus.NotEligible to AcceleratorPhase.Application,
              ApplicationStatus.NotSubmitted to AcceleratorPhase.PreScreen,
              ApplicationStatus.P0Eligible to AcceleratorPhase.Application,
              ApplicationStatus.PassedPreScreen to AcceleratorPhase.PreScreen,
              ApplicationStatus.SourcingTeamReview to AcceleratorPhase.Application,
              ApplicationStatus.Submitted to AcceleratorPhase.Application,
          ),
          phasesByState,
      )
    }

    @Test
    fun `throws exception if no permission to read project`() {
      val projectId = insertProject(phase = AcceleratorPhase.Phase0DueDiligence)

      every { user.canReadProject(projectId) } returns false

      assertThrows<ProjectNotFoundException> { fetcher.getProjectPhase(projectId) }
    }

    @Test
    fun `returns null if the project is not in a phase and has no application`() {
      val projectId = insertProject()
      assertNull(fetcher.getProjectPhase(projectId), "Project phase")
    }
  }
}
