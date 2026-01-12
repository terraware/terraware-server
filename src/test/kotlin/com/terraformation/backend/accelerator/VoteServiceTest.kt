package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.ProjectCohortFetcher
import com.terraformation.backend.accelerator.db.VoteStore
import com.terraformation.backend.accelerator.event.CohortPhaseUpdatedEvent
import com.terraformation.backend.accelerator.event.CohortProjectAddedEvent
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
  private val eventPublisher = TestEventPublisher()

  private val systemUser: SystemUser by lazy { SystemUser(usersDao) }

  private val service: VoteService by lazy {
    VoteService(
        CohortStore(clock, cohortsDao, dslContext, eventPublisher),
        dslContext,
        systemUser,
        VoteStore(clock, dslContext, ProjectCohortFetcher(dslContext)),
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
  inner class CohortProjectAdded {
    @Test
    fun `inserts voters`() {
      val cohortId1 = insertCohort()
      val projectId1 = insertProject(cohortId = cohortId1)

      // Should leave these alone
      insertProject(cohortId = cohortId1)
      val otherCohortId = insertCohort()
      insertProject(cohortId = otherCohortId)

      service.on(CohortProjectAddedEvent(user.userId, cohortId1, projectId1))

      assertTableEquals(
          setOf(
              votesRecord(userId = voter1, projectId = projectId1),
              votesRecord(userId = voter2, projectId = projectId1),
          )
      )
    }

    @Test
    fun `listens for event`() {
      assertIsEventListener<CohortProjectAddedEvent>(service)
    }
  }

  @Nested
  inner class CohortPhaseUpdated {
    @Test
    fun `inserts voters`() {
      val phase = CohortPhase.Phase1FeasibilityStudy
      val cohortId1 = insertCohort(phase = phase)
      val projectId1 = insertProject(cohortId = cohortId1)
      val projectId2 = insertProject(cohortId = cohortId1)

      // Should leave these alone
      val otherCohortId = insertCohort()
      insertProject(cohortId = otherCohortId)
      insertVote(projectId1, user = voter1)

      service.on(CohortPhaseUpdatedEvent(cohortId1, phase))

      assertTableEquals(
          setOf(
              // First row is inserted by test, not by service
              votesRecord(
                  userId = voter1,
                  projectId = projectId1,
                  phase = CohortPhase.Phase0DueDiligence,
                  createdBySystemUser = false,
              ),
              votesRecord(userId = voter1, projectId = projectId1, phase = phase),
              votesRecord(userId = voter2, projectId = projectId1, phase = phase),
              votesRecord(userId = voter1, projectId = projectId2, phase = phase),
              votesRecord(userId = voter2, projectId = projectId2, phase = phase),
          )
      )
    }

    @Test
    fun `listens for event`() {
      assertIsEventListener<CohortPhaseUpdatedEvent>(service)
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
