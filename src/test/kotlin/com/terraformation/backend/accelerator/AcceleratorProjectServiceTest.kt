package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.model.AcceleratorProjectModel
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.AcceleratorProjectNotFoundException
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.ProjectNotFoundException
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.mockUser
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class AcceleratorProjectServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val service: AcceleratorProjectService by lazy { AcceleratorProjectService(dslContext) }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)
    insertCohort(phase = CohortPhase.Phase1FeasibilityStudy)
    insertParticipant(cohortId = inserted.cohortId)
    insertProject(countryCode = "KE", participantId = inserted.participantId)
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

    every { user.canReadAllAcceleratorDetails() } returns true
    every { user.canReadProjectAcceleratorDetails(any()) } returns true
  }

  @Test
  fun `fetches one by Id, or throws not found exception`() {
    val phase = cohortsDao.fetchOneById(inserted.cohortId)!!.phaseId!!
    assertEquals(
        AcceleratorProjectModel(
            cohortId = inserted.cohortId,
            cohortName = cohortsDao.fetchOneById(inserted.cohortId)!!.name!!,
            participantId = inserted.participantId,
            participantName = participantsDao.fetchOneById(inserted.participantId)!!.name!!,
            phase = phase,
            projectId = inserted.projectId,
            projectName = projectsDao.fetchOneById(inserted.projectId)!!.name!!,
            voteDecisions =
                mapOf(
                    CohortPhase.Phase0DueDiligence to VoteOption.Yes,
                    CohortPhase.Phase1FeasibilityStudy to VoteOption.No,
                ),
        ),
        service.fetchOneById(inserted.projectId))

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
                participantId = inserted.participantId,
                participantName = participantsDao.fetchOneById(inserted.participantId)!!.name!!,
                phase = phase,
                projectId = inserted.projectId,
                projectName = projectsDao.fetchOneById(inserted.projectId)!!.name!!,
                voteDecisions =
                    mapOf(
                        CohortPhase.Phase0DueDiligence to VoteOption.Yes,
                        CohortPhase.Phase1FeasibilityStudy to VoteOption.No,
                    ),
            )),
        service.listAcceleratorProjects())
  }

  @Test
  fun `throws exception if no permission to read one or list accelerator projects`() {
    every { user.canReadAllAcceleratorDetails() } returns false
    assertThrows<AccessDeniedException> { service.listAcceleratorProjects() }

    every { user.canReadProjectAcceleratorDetails(inserted.projectId) } returns false
    every { user.canReadProject(inserted.projectId) } returns true
    assertThrows<AccessDeniedException> { service.fetchOneById(inserted.projectId) }

    every { user.canReadProject(inserted.projectId) } returns false
    assertThrows<ProjectNotFoundException> { service.fetchOneById(inserted.projectId) }
  }
}
