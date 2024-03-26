package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.accelerator.model.AcceleratorProjectModel
import com.terraformation.backend.customer.model.InternalTagIds
import com.terraformation.backend.db.DatabaseTest
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
    insertUser()
    insertOrganization()
    insertOrganizationInternalTag(tagId = InternalTagIds.Accelerator)
    insertCohort(phase = CohortPhase.Phase1FeasibilityStudy)
    insertParticipant(cohortId = inserted.cohortId)
    insertProject(countryCode = "KE", participantId = inserted.participantId)
    insertVoteDecision(
        projectId = inserted.projectId,
        phase = CohortPhase.Phase1FeasibilityStudy,
        voteOption = VoteOption.No,
    )

    every { user.canReadAllAcceleratorDetails() } returns true
  }

  @Test
  fun `returns accelerator projects`() {
    assertEquals(
        listOf(
            AcceleratorProjectModel(
                cohortId = inserted.cohortId,
                cohortName = cohortsDao.fetchOneById(inserted.cohortId)!!.name!!,
                participantId = inserted.participantId,
                participantName = participantsDao.fetchOneById(inserted.participantId)!!.name!!,
                phase = cohortsDao.fetchOneById(inserted.cohortId)!!.phaseId!!,
                projectId = inserted.projectId,
                projectName = projectsDao.fetchOneById(inserted.projectId)!!.name!!,
                voteDecision = VoteOption.No,
            )),
        service.listAcceleratorProjects())
  }

  @Test
  fun `throws exception if no permission to list accelerator projects`() {
    every { user.canReadAllAcceleratorDetails() } returns false

    assertThrows<AccessDeniedException> { service.listAcceleratorProjects() }
  }
}
