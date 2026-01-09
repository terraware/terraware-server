package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsDatabaseUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.ParticipantStore
import com.terraformation.backend.accelerator.db.ProjectCohortFetcher
import com.terraformation.backend.accelerator.db.VoteStore
import com.terraformation.backend.accelerator.event.CohortParticipantAddedEvent
import com.terraformation.backend.accelerator.event.CohortPhaseUpdatedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectAddedEvent
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
        ParticipantStore(clock, dslContext, eventPublisher, participantsDao),
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
  inner class ParticipantProjectAdded {
    @Test
    fun `inserts voters`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      insertProject(cohortId = cohortId, participantId = participantId) // Should be ignored
      val projectId = insertProject(cohortId = cohortId, participantId = participantId)

      service.on(ParticipantProjectAddedEvent(user.userId, participantId, projectId))

      assertTableEquals(setOf(votesRecord(voter1), votesRecord(voter2)))
    }

    @Test
    fun `does not modify existing voter information`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(cohortId = cohortId, participantId = participantId)

      insertVote(user = voter1, voteOption = VoteOption.No, conditionalInfo = "cond")

      clock.instant = Instant.ofEpochSecond(30)

      service.on(ParticipantProjectAddedEvent(user.userId, participantId, projectId))

      assertTableEquals(
          setOf(
              votesRecord(
                  userId = voter1,
                  createdTime = Instant.EPOCH,
                  voteOption = VoteOption.No,
                  conditionalInfo = "cond",
                  createdBySystemUser = false,
              ),
              votesRecord(voter2),
          )
      )
    }

    @Test
    fun `listens for event`() {
      assertIsEventListener<ParticipantProjectAddedEvent>(service)
    }
  }

  @Nested
  inner class CohortParticipantAdded {
    @Test
    fun `inserts voters`() {
      val cohortId1 = insertCohort()
      val participantId1 = insertParticipant(cohortId = cohortId1)
      val projectId1 = insertProject(cohortId = cohortId1, participantId = participantId1)
      val projectId2 = insertProject(cohortId = cohortId1, participantId = participantId1)

      // Should leave these alone
      val otherParticipantId = insertParticipant(cohortId = cohortId1)
      insertProject(cohortId = cohortId1, participantId = otherParticipantId)
      val otherCohortId = insertCohort()
      val otherCohortParticipantId = insertParticipant(cohortId = otherCohortId)
      insertProject(cohortId = otherCohortId, participantId = otherCohortParticipantId)

      service.on(CohortParticipantAddedEvent(cohortId1, participantId1))

      assertTableEquals(
          setOf(
              votesRecord(userId = voter1, projectId = projectId1),
              votesRecord(userId = voter2, projectId = projectId1),
              votesRecord(userId = voter1, projectId = projectId2),
              votesRecord(userId = voter2, projectId = projectId2),
          )
      )
    }

    @Test
    fun `listens for event`() {
      assertIsEventListener<CohortParticipantAddedEvent>(service)
    }
  }

  @Nested
  inner class CohortPhaseUpdated {
    @Test
    fun `inserts voters`() {
      val phase = CohortPhase.Phase1FeasibilityStudy
      val cohortId1 = insertCohort(phase = phase)
      val participantId1 = insertParticipant(cohortId = cohortId1)
      val projectId1 = insertProject(cohortId = cohortId1, participantId = participantId1)
      val participantId2 = insertParticipant(cohortId = cohortId1)
      val projectId2 = insertProject(cohortId = cohortId1, participantId = participantId2)

      // Should leave these alone
      val otherCohortId = insertCohort()
      val otherCohortParticipantId = insertParticipant(cohortId = otherCohortId)
      insertProject(cohortId = otherCohortId, participantId = otherCohortParticipantId)
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
