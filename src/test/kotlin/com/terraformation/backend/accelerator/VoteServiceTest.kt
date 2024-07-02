package com.terraformation.backend.accelerator

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.TestEventPublisher
import com.terraformation.backend.accelerator.db.CohortStore
import com.terraformation.backend.accelerator.db.ParticipantStore
import com.terraformation.backend.accelerator.db.PhaseChecker
import com.terraformation.backend.accelerator.db.VoteStore
import com.terraformation.backend.accelerator.event.CohortParticipantAddedEvent
import com.terraformation.backend.accelerator.event.CohortPhaseUpdatedEvent
import com.terraformation.backend.accelerator.event.ParticipantProjectAddedEvent
import com.terraformation.backend.assertIsEventListener
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectVotesRow
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class VoteServiceTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val eventPublisher = TestEventPublisher()
  private val service: VoteService by lazy {
    VoteService(
        CohortStore(clock, cohortsDao, dslContext, eventPublisher),
        dslContext,
        ParticipantStore(clock, dslContext, eventPublisher, participantsDao),
        VoteStore(clock, dslContext, PhaseChecker(dslContext)))
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

    every { user.canReadCohort(any()) } returns true
    every { user.canReadCohortParticipants(any()) } returns true
    every { user.canReadParticipant(any()) } returns true
    every { user.canUpdateProjectVotes(any()) } returns true
  }

  @Nested
  inner class ParticipantProjectAdded {
    @Test
    fun `inserts voters`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      insertProject(participantId = participantId) // Should be ignored
      val projectId = insertProject(participantId = participantId)

      service.on(ParticipantProjectAddedEvent(user.userId, participantId, projectId))

      assertEquals(setOf(votesRow(voter1), votesRow(voter2)), projectVotesDao.findAll().toSet())
    }

    @Test
    fun `does not modify existing voter information`() {
      val cohortId = insertCohort()
      val participantId = insertParticipant(cohortId = cohortId)
      val projectId = insertProject(participantId = participantId)

      insertVote(user = voter1, voteOption = VoteOption.No, conditionalInfo = "cond")

      clock.instant = Instant.ofEpochSecond(30)

      service.on(ParticipantProjectAddedEvent(user.userId, participantId, projectId))

      assertEquals(
          setOf(
              votesRow(
                  userId = voter1,
                  createdTime = Instant.EPOCH,
                  voteOption = VoteOption.No,
                  conditionalInfo = "cond"),
              votesRow(voter2)),
          projectVotesDao.findAll().toSet())
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
      val projectId1 = insertProject(participantId = participantId1)
      val projectId2 = insertProject(participantId = participantId1)

      // Should leave these alone
      val otherParticipantId = insertParticipant(cohortId = cohortId1)
      insertProject(participantId = otherParticipantId)
      val otherCohortId = insertCohort()
      val otherCohortParticipantId = insertParticipant(cohortId = otherCohortId)
      insertProject(participantId = otherCohortParticipantId)

      service.on(CohortParticipantAddedEvent(cohortId1, participantId1))

      assertEquals(
          setOf(
              votesRow(userId = voter1, projectId = projectId1),
              votesRow(userId = voter2, projectId = projectId1),
              votesRow(userId = voter1, projectId = projectId2),
              votesRow(userId = voter2, projectId = projectId2),
          ),
          projectVotesDao.findAll().toSet())
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
      val projectId1 = insertProject(participantId = participantId1)
      val participantId2 = insertParticipant(cohortId = cohortId1)
      val projectId2 = insertProject(participantId = participantId2)

      // Should leave these alone
      val otherCohortId = insertCohort()
      val otherCohortParticipantId = insertParticipant(cohortId = otherCohortId)
      insertProject(participantId = otherCohortParticipantId)
      insertVote(projectId1, user = voter1)

      service.on(CohortPhaseUpdatedEvent(cohortId1, phase))

      assertEquals(
          setOf(
              // First row is inserted by test, not by service
              votesRow(
                  userId = voter1, projectId = projectId1, phase = CohortPhase.Phase0DueDiligence),
              votesRow(userId = voter1, projectId = projectId1, phase = phase),
              votesRow(userId = voter2, projectId = projectId1, phase = phase),
              votesRow(userId = voter1, projectId = projectId2, phase = phase),
              votesRow(userId = voter2, projectId = projectId2, phase = phase),
          ),
          projectVotesDao.findAll().toSet())
    }

    @Test
    fun `listens for event`() {
      assertIsEventListener<CohortPhaseUpdatedEvent>(service)
    }
  }

  private fun votesRow(
      userId: UserId,
      projectId: ProjectId = inserted.projectId,
      phase: CohortPhase = CohortPhase.Phase0DueDiligence,
      createdTime: Instant = clock.instant,
      voteOption: VoteOption? = null,
      conditionalInfo: String? = null,
  ): ProjectVotesRow {
    return ProjectVotesRow(
        conditionalInfo = conditionalInfo,
        createdBy = user.userId,
        createdTime = createdTime,
        modifiedBy = user.userId,
        modifiedTime = createdTime,
        phaseId = phase,
        projectId = projectId,
        userId = userId,
        voteOptionId = voteOption,
    )
  }
}
