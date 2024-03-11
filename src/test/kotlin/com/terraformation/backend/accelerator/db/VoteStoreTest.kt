package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.VoteModel
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.SubmissionStatus
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
import org.springframework.security.access.AccessDeniedException

class VoteStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val store: VoteStore by lazy { VoteStore(clock, dslContext, projectVotesDao) }

  data class VoteKey(val userId: UserId, val projectId: ProjectId, val phase: CohortPhase)

  @BeforeEach
  fun setUp() {
    insertUser()
    insertOrganization()

    every { user.canReadProject(any()) } returns true
    every { user.canUpdateProjectVotes(any()) } returns true
  }

  @Nested
  inner class FetchAllVotesByProject {
    @Test
    fun `fetches with no vote`() {
      val projectId = insertProject()
      clock.instant = Instant.EPOCH.plusSeconds(500)

      assertEquals(listOf<VoteModel>(), store.fetchAllVotesByProject(projectId))
    }

    @Test
    fun `fetches all votes`() {
      val projectId = insertProject()
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      clock.instant = Instant.EPOCH.plusSeconds(500)

      val user100 = insertUser(100)
      val user200 = insertUser(200)
      val user300 = insertUser(300)
      val vote100 = VoteOption.No
      val vote200 = VoteOption.Yes
      val vote300 = VoteOption.Conditional
      val condition300 = "I will not vote yes until this happens."

      insertVote(projectId, phase, user100, vote100)
      insertVote(projectId, phase, user200, vote200)
      insertVote(projectId, phase, user300, vote300, condition300)

      assertEquals(
          listOf(
              VoteModel(projectId, phase, user100, vote100, null),
              VoteModel(projectId, phase, user200, vote200, null),
              VoteModel(projectId, phase, user300, vote300, condition300)),
          store.fetchAllVotesByProject(projectId))
    }

    @Test
    fun `throws exception on fetch if no permission to update votes `() {
      val projectId = insertProject()

      every { user.canUpdateProjectVotes(projectId) } returns false

      org.junit.jupiter.api.assertThrows<AccessDeniedException> {
        store.fetchAllVotesByProject(projectId)
      }
    }
  }

  @Nested
  inner class Delete {
    @Test
    fun `delete only vote`() {
      val projectId = insertProject()
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy

      val newUser = insertUser(100)
      val vote = VoteOption.No

      insertVote(projectId, phase, newUser, vote)

      store.delete(projectId, phase, newUser)

      assertEquals(listOf<ProjectVotesRow>(), projectVotesDao.findAll())
    }

    @Test
    fun `delete one vote from multiple voters`() {
      val projectId = insertProject()
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      clock.instant = Instant.EPOCH.plusSeconds(500)

      val user100 = insertUser(100)
      val user200 = insertUser(200)
      val user300 = insertUser(300)
      val vote100 = VoteOption.No
      val vote200 = VoteOption.Yes
      val vote300 = VoteOption.Conditional
      val condition300 = "I will not vote yes until this happens."

      insertVote(projectId, phase, user100, vote100, createdTime = clock.instant)
      insertVote(projectId, phase, user200, vote200, createdTime = clock.instant)
      insertVote(projectId, phase, user300, vote300, condition300, createdTime = clock.instant)

      store.delete(projectId, phase, user200)

      assertEquals(
          listOf(
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = user100,
                  voteOptionId = vote100,
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = user300,
                  voteOptionId = vote300,
                  conditionalInfo = condition300,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          projectVotesDao.findAll())
    }

    @Test
    fun `delete one phase of votes from multiple phases`() {
      val projectId = insertProject()
      val phase0: CohortPhase = CohortPhase.Phase0DueDiligence
      val phase1: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      clock.instant = Instant.EPOCH.plusSeconds(500)

      val user100 = insertUser(100)
      val user200 = insertUser(200)

      val votes: MutableMap<VoteKey, VoteOption> = mutableMapOf()

      votes[VoteKey(user100, projectId, phase0)] = VoteOption.No
      votes[VoteKey(user200, projectId, phase0)] = VoteOption.No

      votes[VoteKey(user100, projectId, phase1)] = VoteOption.No
      votes[VoteKey(user200, projectId, phase1)] = VoteOption.No

      insertVote(
          projectId,
          phase0,
          user100,
          votes[VoteKey(user100, projectId, phase0)],
          createdTime = clock.instant)
      insertVote(
          projectId,
          phase0,
          user200,
          votes[VoteKey(user200, projectId, phase0)],
          createdTime = clock.instant)
      insertVote(
          projectId,
          phase1,
          user100,
          votes[VoteKey(user100, projectId, phase1)],
          createdTime = clock.instant)
      insertVote(
          projectId,
          phase1,
          user200,
          votes[VoteKey(user200, projectId, phase1)],
          createdTime = clock.instant)

      store.delete(projectId, phase1)

      assertEquals(
          listOf(
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase0,
                  userId = user100,
                  voteOptionId = votes[VoteKey(user100, projectId, phase0)],
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase0,
                  userId = user200,
                  voteOptionId = votes[VoteKey(user200, projectId, phase0)],
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              )),
          projectVotesDao.findAll())
    }

    @Test
    fun `delete one projects of votes from multiple projects`() {
      val project1 = insertProject()
      val project2 = insertProject()

      val phase0: CohortPhase = CohortPhase.Phase0DueDiligence
      val phase1: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      clock.instant = Instant.EPOCH.plusSeconds(500)

      val user100 = insertUser(100)
      val user200 = insertUser(200)

      val votes: MutableMap<VoteKey, VoteOption> = mutableMapOf()

      votes[VoteKey(user100, project1, phase0)] = VoteOption.No
      votes[VoteKey(user200, project1, phase0)] = VoteOption.No
      votes[VoteKey(user100, project1, phase1)] = VoteOption.Yes
      votes[VoteKey(user200, project1, phase1)] = VoteOption.Yes

      votes[VoteKey(user100, project2, phase0)] = VoteOption.Yes
      votes[VoteKey(user200, project2, phase0)] = VoteOption.Yes
      votes[VoteKey(user100, project2, phase1)] = VoteOption.No
      votes[VoteKey(user200, project2, phase1)] = VoteOption.No

      insertVote(
          project1,
          phase0,
          user100,
          votes[VoteKey(user100, project1, phase0)],
          createdTime = clock.instant)
      insertVote(
          project1,
          phase0,
          user200,
          votes[VoteKey(user200, project1, phase0)],
          createdTime = clock.instant)
      insertVote(
          project1,
          phase1,
          user100,
          votes[VoteKey(user100, project1, phase1)],
          createdTime = clock.instant)
      insertVote(
          project1,
          phase1,
          user200,
          votes[VoteKey(user200, project1, phase1)],
          createdTime = clock.instant)

      insertVote(
          project2,
          phase0,
          user100,
          votes[VoteKey(user100, project2, phase0)],
          createdTime = clock.instant)
      insertVote(
          project2,
          phase0,
          user200,
          votes[VoteKey(user200, project2, phase0)],
          createdTime = clock.instant)
      insertVote(
          project2,
          phase1,
          user100,
          votes[VoteKey(user100, project2, phase1)],
          createdTime = clock.instant)
      insertVote(
          project2,
          phase1,
          user200,
          votes[VoteKey(user200, project2, phase1)],
          createdTime = clock.instant)

      store.delete(project2)

      assertEquals(
          listOf(
              ProjectVotesRow(
                  projectId = project1,
                  phaseId = phase0,
                  userId = user100,
                  voteOptionId = votes[VoteKey(user100, project1, phase0)],
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ProjectVotesRow(
                  projectId = project1,
                  phaseId = phase0,
                  userId = user200,
                  voteOptionId = votes[VoteKey(user200, project1, phase0)],
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ProjectVotesRow(
                  projectId = project1,
                  phaseId = phase1,
                  userId = user100,
                  voteOptionId = votes[VoteKey(user100, project1, phase1)],
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = clock.instant,
                  modifiedBy = user.userId,
                  modifiedTime = clock.instant,
              ),
              ProjectVotesRow(
                  projectId = project1,
                  phaseId = phase1,
                  userId = user200,
                  voteOptionId = votes[VoteKey(user200, project1, phase1)],
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
      val projectId = insertProject()
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      val newUser = insertUser(100)
      val vote = VoteOption.No
      insertVote(projectId, phase, newUser, vote)

      every { user.canUpdateProjectVotes(projectId) } returns false

      org.junit.jupiter.api.assertThrows<AccessDeniedException> {
        store.delete(projectId, phase, newUser)
      }
    }
  }

  @Nested
  inner class Upsert {
    @Test
    fun `creates blank vote for self`() {
      val projectId = insertProject()

      clock.instant = Instant.EPOCH.plusSeconds(500)
      val phase: CohortPhase = CohortPhase.Phase0DueDiligence
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
      val projectId = insertProject()
      val otherUser = insertUser(100)

      clock.instant = Instant.EPOCH.plusSeconds(500)
      val phase: CohortPhase = CohortPhase.Phase0DueDiligence
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
      val projectId = insertProject()
      val otherUser = insertUser(100)

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
      val projectId = insertProject()
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy

      val newUser = insertUser(100)
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
      val projectId = insertProject()
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy

      val createdTime = Instant.EPOCH.plusSeconds(500)

      val user100 = insertUser(100)
      val user200 = insertUser(200)
      val user300 = insertUser(300)
      val vote100 = VoteOption.No
      val vote200 = VoteOption.Yes
      val vote300 = VoteOption.Conditional
      val condition300 = "I will not vote yes until this happens."

      insertVote(projectId, phase, user100, vote100, createdTime = createdTime)
      insertVote(projectId, phase, user200, vote200, createdTime = createdTime)
      insertVote(projectId, phase, user300, vote300, condition300, createdTime = createdTime)

      val newVote = VoteOption.Yes
      val newCondition = null

      // Time elapse before update
      clock.instant = Instant.EPOCH.plusSeconds(1000)
      store.upsert(projectId, phase, user300, newVote, newCondition)

      assertEquals(
          listOf(
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = user100,
                  voteOptionId = vote100,
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = createdTime,
                  modifiedBy = user.userId,
                  modifiedTime = createdTime,
              ),
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = user200,
                  voteOptionId = vote200,
                  conditionalInfo = null,
                  createdBy = user.userId,
                  createdTime = createdTime,
                  modifiedBy = user.userId,
                  modifiedTime = createdTime,
              ),
              ProjectVotesRow(
                  projectId = projectId,
                  phaseId = phase,
                  userId = user300,
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
      val projectId = insertProject()
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
      val project1 = insertProject()
      val project2 = insertProject()
      val phase: CohortPhase = CohortPhase.Phase0DueDiligence

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
    fun `throws exception on upsert if no permission to update votes `() {
      val projectId = insertProject()
      val phase: CohortPhase = CohortPhase.Phase1FeasibilityStudy
      val newUser = insertUser(100)
      val vote = VoteOption.No
      insertVote(projectId, phase, newUser, vote)

      every { user.canUpdateProjectVotes(projectId) } returns false

      org.junit.jupiter.api.assertThrows<AccessDeniedException> {
        store.upsert(projectId, phase, newUser, null, null)
      }
    }
  }
}
