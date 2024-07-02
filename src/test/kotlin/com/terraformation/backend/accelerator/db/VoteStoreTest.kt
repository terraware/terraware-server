package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.VoteDecisionModel
import com.terraformation.backend.accelerator.model.VoteModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectVoteDecisionsRow
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
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class VoteStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val store: VoteStore by lazy { VoteStore(clock, dslContext, PhaseChecker(dslContext)) }

  data class VoteKey(val userId: UserId, val projectId: ProjectId, val phase: CohortPhase)

  @BeforeEach
  fun setUp() {
    insertOrganization()
    val cohortId = insertCohort(phase = CohortPhase.Phase1FeasibilityStudy)
    insertParticipant(cohortId = cohortId)

    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectVotes(any()) } returns true
    every { user.canUpdateProjectVotes(any()) } returns true
  }

  @Nested
  inner class FetchAllVotes {
    @Test
    fun `fetches with no vote`() {
      val projectId = insertProject(participantId = inserted.participantId)
      clock.instant = Instant.EPOCH.plusSeconds(500)

      assertEquals(emptyList<VoteModel>(), store.fetchAllVotes(projectId))
    }

    @Test
    fun `fetches all votes`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      clock.instant = Instant.EPOCH.plusSeconds(500)

      val email1 = "batman@terraformation.com"
      val firstName1 = "Bruce"
      val lastName1 = "Wayne"

      val email2 = "superman@terraformation.com"
      val firstName2 = "Clark"
      val lastName2 = "Kent"

      val email3 = "harley.quinn@terraformation.com"
      val firstName3 = "Harley"
      val lastName3 = "Quinn"

      val user1 = insertUser(email = email1, firstName = firstName1, lastName = lastName1)
      val user2 = insertUser(email = email2, firstName = firstName2, lastName = lastName2)
      val user3 = insertUser(email = email3, firstName = firstName3, lastName = lastName3)
      val vote1 = VoteOption.No
      val vote2 = VoteOption.Yes
      val vote3 = VoteOption.Conditional
      val condition3 = "I will not vote yes until this happens."

      insertVote(projectId, phase, user1, vote1)
      insertVote(projectId, phase, user2, vote2)
      insertVote(projectId, phase, user3, vote3, condition3)

      assertEquals(
          listOf(
              VoteModel(
                  conditionalInfo = null,
                  email = email1,
                  firstName = firstName1,
                  lastName = lastName1,
                  phase = phase,
                  userId = user1,
                  voteOption = vote1,
              ),
              VoteModel(
                  conditionalInfo = null,
                  email = email2,
                  firstName = firstName2,
                  lastName = lastName2,
                  phase = phase,
                  userId = user2,
                  voteOption = vote2,
              ),
              VoteModel(
                  conditionalInfo = condition3,
                  email = email3,
                  firstName = firstName3,
                  lastName = lastName3,
                  phase = phase,
                  userId = user3,
                  voteOption = vote3,
              )),
          store.fetchAllVotes(projectId))
    }

    @Test
    fun `fetches votes by phase`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase0: CohortPhase = CohortPhase.Phase0DueDiligence
      val phase1: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      clock.instant = Instant.EPOCH.plusSeconds(500)

      val email1 = "batman@terraformation.com"
      val firstName1 = "Bruce"
      val lastName1 = "Wayne"

      val email2 = "superman@terraformation.com"
      val firstName2 = "Clark"
      val lastName2 = "Kent"

      val user1 = insertUser(email = email1, firstName = firstName1, lastName = lastName1)
      val user2 = insertUser(email = email2, firstName = firstName2, lastName = lastName2)

      val votes: MutableMap<VoteKey, VoteOption> = mutableMapOf()

      votes[VoteKey(user1, projectId, phase0)] = VoteOption.No
      votes[VoteKey(user2, projectId, phase0)] = VoteOption.No

      votes[VoteKey(user1, projectId, phase1)] = VoteOption.Yes
      votes[VoteKey(user2, projectId, phase1)] = VoteOption.Yes

      insertVote(projectId, phase0, user1, votes[VoteKey(user1, projectId, phase0)])
      insertVote(projectId, phase0, user2, votes[VoteKey(user2, projectId, phase0)])

      insertVote(projectId, phase1, user1, votes[VoteKey(user1, projectId, phase1)])
      insertVote(projectId, phase1, user2, votes[VoteKey(user2, projectId, phase1)])

      assertEquals(
          listOf(
              VoteModel(
                  conditionalInfo = null,
                  email = email1,
                  firstName = firstName1,
                  lastName = lastName1,
                  phase = phase0,
                  userId = user1,
                  voteOption = votes[VoteKey(user1, projectId, phase0)],
              ),
              VoteModel(
                  conditionalInfo = null,
                  email = email2,
                  firstName = firstName2,
                  lastName = lastName2,
                  phase = phase0,
                  userId = user2,
                  voteOption = votes[VoteKey(user2, projectId, phase0)],
              )),
          store.fetchAllVotes(projectId, phase0))
    }

    @Test
    fun `throws exception on fetch if no permission to read votes `() {
      val projectId = insertProject(participantId = inserted.participantId)

      every { user.canReadProjectVotes(projectId) } returns false

      assertThrows<AccessDeniedException> { store.fetchAllVotes(projectId) }
    }
  }

  @Nested
  inner class FetchAllVoteDecisions {
    @Test
    fun `fetches with no vote`() {
      val projectId = insertProject(participantId = inserted.participantId)
      clock.instant = Instant.EPOCH.plusSeconds(500)

      assertEquals(emptyList<VoteDecisionModel>(), store.fetchAllVoteDecisions(projectId))
    }

    @Test
    fun `fetches all vote decisions`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy

      insertVoteDecision(projectId, phase, VoteOption.Yes, clock.instant)

      assertEquals(
          listOf(
              VoteDecisionModel(phase, clock.instant, VoteOption.Yes),
          ),
          store.fetchAllVoteDecisions(projectId))
    }

    @Test
    fun `fetches vote decisions by phase`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase0: CohortPhase = CohortPhase.Phase0DueDiligence
      val phase1: CohortPhase = CohortPhase.Phase1FeasibilityStudy

      insertVoteDecision(projectId, phase0, VoteOption.Yes, clock.instant)
      insertVoteDecision(projectId, phase1, VoteOption.No, clock.instant)

      assertEquals(
          listOf(
              VoteDecisionModel(phase0, clock.instant, VoteOption.Yes),
          ),
          store.fetchAllVoteDecisions(projectId, phase0))
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `delete only vote`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy

      val newUser = insertUser()
      val vote = VoteOption.No

      insertVote(projectId, phase, newUser, vote)

      store.delete(projectId, phase, newUser)

      assertEquals(emptyList<ProjectVotesRow>(), projectVotesDao.findAll())
    }

    @Test
    fun `delete one vote from multiple voters`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      clock.instant = Instant.EPOCH.plusSeconds(500)

      val user1 = insertUser()
      val user2 = insertUser()
      val user3 = insertUser()
      val vote1 = VoteOption.No
      val vote2 = VoteOption.Yes
      val vote3 = VoteOption.Conditional
      val condition3 = "I will not vote yes until this happens."

      insertVote(projectId, phase, user1, vote1, createdTime = clock.instant)
      insertVote(projectId, phase, user2, vote2, createdTime = clock.instant)
      insertVote(projectId, phase, user3, vote3, condition3, createdTime = clock.instant)

      store.delete(projectId, phase, user2)

      assertEquals(
          listOf(
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = user1,
                  voteOptionId = vote1,
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = user3,
                  voteOptionId = vote3,
                  conditionalInfo = condition3,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          projectVotesDao.findAll())
    }

    @Test
    fun `delete one phase of votes from multiple phases`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase0: CohortPhase = CohortPhase.Phase0DueDiligence
      val phase1: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      clock.instant = Instant.EPOCH.plusSeconds(500)

      val user1 = insertUser()
      val user2 = insertUser()

      val votes: MutableMap<VoteKey, VoteOption> = mutableMapOf()

      votes[VoteKey(user1, projectId, phase0)] = VoteOption.No
      votes[VoteKey(user2, projectId, phase0)] = VoteOption.No

      votes[VoteKey(user1, projectId, phase1)] = VoteOption.No
      votes[VoteKey(user2, projectId, phase1)] = VoteOption.No

      insertVote(
          projectId,
          phase0,
          user1,
          votes[VoteKey(user1, projectId, phase0)],
          createdTime = clock.instant)
      insertVote(
          projectId,
          phase0,
          user2,
          votes[VoteKey(user2, projectId, phase0)],
          createdTime = clock.instant)
      insertVote(
          projectId,
          phase1,
          user1,
          votes[VoteKey(user1, projectId, phase1)],
          createdTime = clock.instant)
      insertVote(
          projectId,
          phase1,
          user2,
          votes[VoteKey(user2, projectId, phase1)],
          createdTime = clock.instant)

      store.delete(projectId, phase1)

      assertEquals(
          listOf(
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase0,
                  userId = user1,
                  voteOptionId = votes[VoteKey(user1, projectId, phase0)],
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase0,
                  userId = user2,
                  voteOptionId = votes[VoteKey(user2, projectId, phase0)],
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          projectVotesDao.findAll())
    }

    @Test
    fun `throws exception on delete if no permission to update votes `() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      val newUser = insertUser()
      val vote = VoteOption.No
      insertVote(projectId, phase, newUser, vote)

      every { user.canUpdateProjectVotes(projectId) } returns false

      assertThrows<AccessDeniedException> { store.delete(projectId, phase, newUser) }
    }

    @Test
    fun `throws exception on delete if project not in cohort `() {
      val projectId = insertProject()
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      val newUser = insertUser()
      val vote = VoteOption.No
      insertVote(projectId, phase, newUser, vote)

      assertThrows<ProjectNotInCohortException> { store.delete(projectId, phase, newUser) }
    }

    @Test
    fun `throws exception on delete for wrong phase `() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase0DueDiligence
      val newUser = insertUser()
      val vote = VoteOption.No
      insertVote(projectId, phase, newUser, vote)

      assertThrows<ProjectNotInCohortPhaseException> { store.delete(projectId, phase, newUser) }
    }

    @Test
    fun `computes vote decision on deleting one vote from many`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy

      val user1 = insertUser()
      val user2 = insertUser()
      val vote1 = VoteOption.No
      val vote2 = VoteOption.Yes

      insertVote(projectId, phase, user1, vote1)
      insertVote(projectId, phase, user2, vote2)
      insertVoteDecision(projectId, phase, null, Instant.EPOCH)

      clock.instant = Instant.EPOCH.plusSeconds(500)

      store.delete(projectId, phase, user2)
      assertEquals(
          listOf(ProjectVoteDecisionsRow(projectId, phase, vote1, clock.instant)),
          projectVoteDecisionDao.findAll())
    }

    @Test
    fun `computes vote decision on deleting only vote`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy

      val newUser = insertUser()
      val vote = VoteOption.No

      insertVote(projectId, phase, newUser, vote)
      insertVoteDecision(projectId, phase, vote, Instant.EPOCH)

      clock.instant = Instant.EPOCH.plusSeconds(500)

      store.delete(projectId, phase, newUser)
      assertEquals(
          listOf(ProjectVoteDecisionsRow(projectId, phase, null, clock.instant)),
          projectVoteDecisionDao.findAll())
    }
  }

  @Nested
  inner class Upsert {
    @Test
    fun `creates blank vote for self`() {
      val projectId = insertProject(participantId = inserted.participantId)

      clock.instant = Instant.EPOCH.plusSeconds(500)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      store.upsert(projectId, phase, user.userId, null, null)

      assertEquals(
          listOf(
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = user.userId,
                  voteOptionId = null,
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          projectVotesDao.findAll())
    }

    @Test
    fun `creates blank vote for other user`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val otherUser = insertUser()

      clock.instant = Instant.EPOCH.plusSeconds(500)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      store.upsert(projectId, phase, otherUser, null, null)

      assertEquals(
          listOf(
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = otherUser,
                  voteOptionId = null,
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          projectVotesDao.findAll())
    }

    @Test
    fun `creates conditional vote for other user`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val otherUser = insertUser()

      clock.instant = Instant.EPOCH.plusSeconds(500)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      val voteOption: VoteOption = VoteOption.Conditional
      val conditionalInfo = "Reason why the vote is a maybe"
      store.upsert(projectId, phase, otherUser, voteOption, conditionalInfo)

      assertEquals(
          listOf(
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = otherUser,
                  voteOptionId = voteOption,
                  conditionalInfo = conditionalInfo,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          projectVotesDao.findAll())
    }

    @Test
    fun `update only voter selection`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy

      val newUser = insertUser()
      val vote = VoteOption.No
      val newVote = VoteOption.Conditional
      val newCondition = "I will not vote yes until this happens."

      val createdTime = clock.instant

      insertVote(projectId, phase, newUser, vote)
      clock.instant = Instant.EPOCH.plusSeconds(500)

      store.upsert(projectId, phase, newUser, newVote, newCondition)

      assertEquals(
          listOf(
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = newUser,
                  voteOptionId = newVote,
                  conditionalInfo = newCondition,
                  createdBy = user.userId,
                  createdTime = createdTime,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          projectVotesDao.findAll())
    }

    @Test
    fun `update voter selection with multiple voters`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy

      val createdTime = Instant.EPOCH.plusSeconds(500)

      val user1 = insertUser()
      val user2 = insertUser()
      val user3 = insertUser()
      val vote1 = VoteOption.No
      val vote2 = VoteOption.Yes
      val vote3 = VoteOption.Conditional
      val condition3 = "I will not vote yes until this happens."

      insertVote(projectId, phase, user1, vote1, createdTime = createdTime)
      insertVote(projectId, phase, user2, vote2, createdTime = createdTime)
      insertVote(projectId, phase, user3, vote3, condition3, createdTime = createdTime)

      val newVote = VoteOption.Yes
      val newCondition = null

      // Time elapse before update
      clock.instant = Instant.EPOCH.plusSeconds(1000)
      store.upsert(projectId, phase, user3, newVote, newCondition)

      assertEquals(
          listOf(
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = user1,
                  voteOptionId = vote1,
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = createdTime,
                  modifiedBy = user.userId,
                  modifiedTime = createdTime,
              ),
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = user2,
                  voteOptionId = vote2,
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = createdTime,
                  modifiedBy = user.userId,
                  modifiedTime = createdTime,
              ),
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = user3,
                  voteOptionId = newVote,
                  conditionalInfo = newCondition,
                  createdBy = user.userId,
                  createdTime = createdTime,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          projectVotesDao.findAll())
    }

    @Test
    fun `update voter selection for multiple phases`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase0: CohortPhase = CohortPhase.Phase0DueDiligence
      val phase1: CohortPhase = CohortPhase.Phase1FeasibilityStudy

      val createdTime = Instant.EPOCH.plusSeconds(500)

      val vote1 = VoteOption.No
      val vote2 = VoteOption.No

      insertVote(projectId, phase0, user.userId, vote1, createdTime = createdTime)
      insertVote(projectId, phase1, user.userId, vote2, createdTime = createdTime)

      val newVote = VoteOption.Yes

      // Time elapse before update
      clock.instant = Instant.EPOCH.plusSeconds(1000)

      store.upsert(projectId, phase1, user.userId, newVote)

      assertEquals(
          listOf(
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase0,
                  userId = user.userId,
                  voteOptionId = vote1,
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = createdTime,
                  modifiedBy = user.userId,
                  modifiedTime = createdTime,
              ),
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase1,
                  userId = user.userId,
                  voteOptionId = newVote,
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = createdTime,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          projectVotesDao.findAll())
    }

    @Test
    fun `update voter selection for multiple projects`() {
      val project1 = insertProject(participantId = inserted.participantId)
      val project2 = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy

      val createdTime = Instant.EPOCH.plusSeconds(500)

      val vote1 = VoteOption.No
      val vote2 = VoteOption.No

      insertVote(project1, phase, user.userId, vote1, createdTime = createdTime)
      insertVote(project2, phase, user.userId, vote2, createdTime = createdTime)

      val newVote = VoteOption.Yes

      // Time elapse before update
      clock.instant = Instant.EPOCH.plusSeconds(1000)

      store.upsert(project2, phase, user.userId, newVote)

      assertEquals(
          listOf(
              ProjectVotesRow(
                  projectId = project1,
                  phaseId = phase,
                  userId = user.userId,
                  voteOptionId = vote1,
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = createdTime,
                  modifiedBy = user.userId,
                  modifiedTime = createdTime,
              ),
              ProjectVotesRow(
                  projectId = project2,
                  phaseId = phase,
                  userId = user.userId,
                  voteOptionId = newVote,
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = createdTime,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          projectVotesDao.findAll())
    }

    @Test
    fun `throws exception on upsert if no permission to update votes`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      val newUser = insertUser()
      val vote = VoteOption.No
      insertVote(projectId, phase, newUser, vote)

      every { user.canUpdateProjectVotes(projectId) } returns false

      assertThrows<AccessDeniedException> { store.upsert(projectId, phase, newUser, null, null) }
    }

    @Test
    fun `throws exception on upsert if project not in cohort`() {
      val projectId = insertProject()
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      val newUser = insertUser()
      val vote = VoteOption.No
      insertVote(projectId, phase, newUser, vote)

      assertThrows<ProjectNotInCohortException> {
        store.upsert(projectId, phase, newUser, null, null)
      }
    }

    @Test
    fun `throws exception on upsert if project in wrong phase`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase0DueDiligence
      val newUser = insertUser()
      val vote = VoteOption.No
      insertVote(projectId, phase, newUser, vote)

      assertThrows<ProjectNotInCohortPhaseException> {
        store.upsert(projectId, phase, newUser, null, null)
      }
    }

    @Test
    fun `computes vote decision on creating one vote`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      clock.instant = Instant.EPOCH.plusSeconds(500)

      val newUser = insertUser()
      val vote = VoteOption.Yes

      store.upsert(projectId, phase, newUser, vote, null)

      assertEquals(
          listOf(ProjectVoteDecisionsRow(projectId, phase, vote, clock.instant)),
          projectVoteDecisionDao.findAll())
    }

    @Test
    fun `computes vote decision on creating one null vote`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      clock.instant = Instant.EPOCH.plusSeconds(500)

      val newUser = insertUser()

      store.upsert(projectId, phase, newUser, null, null)

      assertEquals(
          listOf(ProjectVoteDecisionsRow(projectId, phase, null, clock.instant)),
          projectVoteDecisionDao.findAll())
    }

    @Test
    fun `computes vote decision on updating one vote`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      clock.instant = Instant.EPOCH.plusSeconds(500)

      val newUser = insertUser()
      val vote = VoteOption.Yes
      val newVote = VoteOption.No

      insertVote(projectId, phase, newUser, vote)
      insertVoteDecision(projectId, phase, vote, Instant.EPOCH)

      store.upsert(projectId, phase, newUser, newVote, null)

      assertEquals(
          listOf(ProjectVoteDecisionsRow(projectId, phase, newVote, clock.instant)),
          projectVoteDecisionDao.findAll())
    }

    @Test
    fun `computes vote decision on upserting to a tie`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      clock.instant = Instant.EPOCH.plusSeconds(500)

      val user1 = insertUser()
      val vote1 = VoteOption.Yes

      val user2 = insertUser()
      val vote2 = VoteOption.No

      insertVote(projectId, phase, user1, vote1)
      insertVoteDecision(projectId, phase, vote1, Instant.EPOCH)

      store.upsert(projectId, phase, user2, vote2, null)

      assertEquals(
          listOf(ProjectVoteDecisionsRow(projectId, phase, null, clock.instant)),
          projectVoteDecisionDao.findAll())
    }

    @Test
    fun `computes vote decision on updating to no vote`() {
      val projectId = insertProject(participantId = inserted.participantId)
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      clock.instant = Instant.EPOCH.plusSeconds(500)

      val user1 = insertUser()
      val user2 = insertUser()
      val vote = VoteOption.Yes

      insertVote(projectId, phase, user1, vote)
      insertVote(projectId, phase, user2, vote)
      insertVoteDecision(projectId, phase, vote, Instant.EPOCH)

      store.upsert(projectId, phase, user2, null, null)

      assertEquals(
          listOf(ProjectVoteDecisionsRow(projectId, phase, null, clock.instant)),
          projectVoteDecisionDao.findAll())
    }
  }
}
