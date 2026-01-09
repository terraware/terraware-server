package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.accelerator.model.AcceleratorProjectModel
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.AcceleratorProjectNotFoundException
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.default_schema.GlobalRole
import com.terraformation.backend.db.default_schema.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class AcceleratorProjectServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val service: AcceleratorProjectService by lazy { AcceleratorProjectService(dslContext) }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)
    insertCohort(phase = CohortPhase.Phase1FeasibilityStudy)
    insertProject(cohortId = inserted.cohortId, countryCode = "KE")
    insertVoteDecision(
        projectId = inserted.projectId,
        phase = CohortPhase.Phase0DueDiligence,
        voteOption = VoteOption.Yes,
    )
    insertVoteDecision(
        projectId = inserted.projectId,
        phase = CohortPhase.Phase1FeasibilityStudy,
        voteOption = VoteOption.No,
    )
    insertVoteDecision(
        projectId = inserted.projectId,
        phase = CohortPhase.Phase2PlanAndScale,
        voteOption = null,
    )

    insertUserGlobalRole(role = GlobalRole.ReadOnly)
  }

  @Test
  fun `fetches one by Id, or throws not found exception`() {
    val phase = cohortsDao.fetchOneById(inserted.cohortId)!!.phaseId!!
    assertEquals(
        AcceleratorProjectModel(
            cohortId = inserted.cohortId,
            cohortName = cohortsDao.fetchOneById(inserted.cohortId)!!.name!!,
            phase = phase,
            projectId = inserted.projectId,
            projectName = projectsDao.fetchOneById(inserted.projectId)!!.name!!,
            voteDecisions =
                mapOf(
                    CohortPhase.Phase0DueDiligence to VoteOption.Yes,
                    CohortPhase.Phase1FeasibilityStudy to VoteOption.No,
                ),
        ),
        service.fetchOneById(inserted.projectId),
    )

    val otherProjectId = insertProject()
    assertThrows<AcceleratorProjectNotFoundException> { service.fetchOneById(otherProjectId) }
  }

  @Test
  fun `returns accelerator projects`() {
    val phase = cohortsDao.fetchOneById(inserted.cohortId)!!.phaseId!!
    assertEquals(
        listOf(
            AcceleratorProjectModel(
                cohortId = inserted.cohortId,
                cohortName = cohortsDao.fetchOneById(inserted.cohortId)!!.name!!,
                phase = phase,
                projectId = inserted.projectId,
                projectName = projectsDao.fetchOneById(inserted.projectId)!!.name!!,
                voteDecisions =
                    mapOf(
                        CohortPhase.Phase0DueDiligence to VoteOption.Yes,
                        CohortPhase.Phase1FeasibilityStudy to VoteOption.No,
                    ),
            )
        ),
        service.listAcceleratorProjects(),
    )
  }

  @Test
  fun `throws exception if no permission to read one or list accelerator projects`() {
    deleteUserGlobalRole(role = GlobalRole.ReadOnly)
    assertThrows<AccessDeniedException> { service.listAcceleratorProjects() }

    insertOrganizationUser(role = Role.Manager)
    assertThrows<AccessDeniedException> { service.fetchOneById(inserted.projectId) }

    deleteOrganizationUser()
    assertThrows<ProjectNotFoundException> { service.fetchOneById(inserted.projectId) }
  }
}
