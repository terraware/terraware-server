package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.db.ProjectPhaseFetcher
import com.terraformation.backend.accelerator.db.VoteStore
import com.terraformation.backend.accelerator.event.ProjectPhaseUpdatedEvent
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.customer.model.SystemUser
import com.terraformation.backend.customer.model.TerrawareUser
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.records.ProjectVotesRecord
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class VoteServiceTest : DatabaseTest(), RunsAsDatabaseUser {
  override lateinit var user: TerrawareUser

  private val clock = TestClock()

  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }

  private val service: VoteService by lazy {
    VoteService(
        systemUser,
        VoteStore(clock, dslContext, ProjectPhaseFetcher(dslContext)),
    )
  }

  private lateinit var voter1: UserId
  private lateinit var voter2: UserId

  @BeforeEach
  fun setUp() {
    insertOrganization()
    voter1 = insertUser()
    voter2 = insertUser()
    insertDefaultVoter(voter1)
    insertDefaultVoter(voter2)
  }

  @Nested
  inner class ProjectPhaseUpdated {
    @Test
    fun `inserts voters`() {
      val projectId1 = insertProject(phase = CohortPhase.Phase0DueDiligence)

      // Should leave this one alone
      insertProject(phase = CohortPhase.Phase0DueDiligence)

      service.on(ProjectPhaseUpdatedEvent(projectId1, CohortPhase.Phase0DueDiligence))

      assertTableEquals(
          setOf(
              votesRecord(userId = voter1, projectId = projectId1),
              votesRecord(userId = voter2, projectId = projectId1),
          )
      )
    }

    @Test
    fun `listens for event`() {
      assertIsEventListener<ProjectPhaseUpdatedEvent>(service)
    }
  }

  private fun votesRecord(
      userId: UserId,
      projectId: ProjectId = inserted.projectId,
      phase: CohortPhase = CohortPhase.Phase0DueDiligence,
      createdTime: Instant = clock.instant,
      voteOption: VoteOption? = null,
      conditionalInfo: String? = null,
      createdBySystemUser: Boolean = true,
  ): ProjectVotesRecord {
    return ProjectVotesRecord(
        conditionalInfo = conditionalInfo,
        createdBy = if (createdBySystemUser) systemUser.userId else user.userId,
        createdTime = createdTime,
        modifiedBy = if (createdBySystemUser) systemUser.userId else user.userId,
        modifiedTime = createdTime,
        phaseId = phase,
        projectId = projectId,
        userId = userId,
        voteOptionId = voteOption,
    )
  }
}
