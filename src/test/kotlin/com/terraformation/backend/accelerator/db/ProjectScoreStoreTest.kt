package com.terraformation.backend.accelerator.db

import com.terraformation.backend.RunsAsUser
import com.terraformation.backend.TestClock
import com.terraformation.backend.accelerator.model.ExistingProjectScoreModel
import com.terraformation.backend.accelerator.model.NewProjectScoreModel
import com.terraformation.backend.assertSetEquals
import com.terraformation.backend.db.DatabaseTest
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ScoreCategory
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectScoresRow
import com.terraformation.backend.mockUser
import io.mockk.every
import java.time.Instant
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException

class ProjectScoreStoreTest : DatabaseTest(), RunsAsUser {
  override val user = mockUser()

  private val clock = TestClock()
  private val store: ProjectScoreStore by lazy {
    ProjectScoreStore(clock, dslContext, ProjectCohortFetcher(dslContext))
  }

  @BeforeEach
  fun setUp() {
    insertOrganization()
    insertCohort(phase = CohortPhase.Phase0DueDiligence)
    insertParticipant(cohortId = inserted.cohortId)

    every { user.canReadProject(any()) } returns true
    every { user.canReadProjectScores(any()) } returns true
    every { user.canUpdateProjectScores(any()) } returns true
  }

  @Nested
  inner class FetchScores {
    @Test
    fun `returns data from all phases by default`() {
      val time1 = Instant.ofEpochSecond(1)
      val time2 = Instant.ofEpochSecond(2)
      val time3 = Instant.ofEpochSecond(3)

      val projectId = insertProject(participantId = inserted.participantId)

      insertProjectScore(
          phase = CohortPhase.Phase0DueDiligence,
          category = ScoreCategory.Legal,
          qualitative = "q1",
          score = 1,
          createdTime = time1,
      )
      insertProjectScore(
          phase = CohortPhase.Phase1FeasibilityStudy,
          category = ScoreCategory.Carbon,
          createdTime = time2,
      )

      insertProject()

      insertProjectScore(
          phase = CohortPhase.Phase0DueDiligence,
          category = ScoreCategory.SocialImpact,
          score = -1,
          createdTime = time3,
      )

      val expected =
          mapOf(
              CohortPhase.Phase0DueDiligence to
                  listOf(ExistingProjectScoreModel(ScoreCategory.Legal, time1, "q1", 1)),
              CohortPhase.Phase1FeasibilityStudy to
                  listOf(ExistingProjectScoreModel(ScoreCategory.Carbon, time2, null, null)),
          )

      val actual = store.fetchScores(projectId)

      assertEquals(expected, actual)
    }

    @Test
    fun `can restrict query to specific phases`() {
      val time1 = Instant.ofEpochSecond(1)
      val time2 = Instant.ofEpochSecond(2)
      val time3 = Instant.ofEpochSecond(3)
      val time4 = Instant.ofEpochSecond(4)

      val projectId = insertProject(participantId = inserted.participantId)

      insertProjectScore(
          phase = CohortPhase.Phase0DueDiligence,
          category = ScoreCategory.Legal,
          qualitative = "q1",
          score = 1,
          createdTime = time1,
      )
      insertProjectScore(
          phase = CohortPhase.Phase0DueDiligence,
          category = ScoreCategory.Carbon,
          score = 2,
          createdTime = time2,
      )
      insertProjectScore(
          phase = CohortPhase.Phase1FeasibilityStudy,
          category = ScoreCategory.Carbon,
          createdTime = time3,
      )
      insertProjectScore(
          phase = CohortPhase.Phase2PlanAndScale,
          category = ScoreCategory.Carbon,
          score = -1,
          createdTime = time4,
      )

      val expected =
          mapOf(
              CohortPhase.Phase0DueDiligence to
                  listOf(
                      ExistingProjectScoreModel(ScoreCategory.Carbon, time2, null, 2),
                      ExistingProjectScoreModel(ScoreCategory.Legal, time1, "q1", 1),
                  ),
              CohortPhase.Phase1FeasibilityStudy to
                  listOf(
                      ExistingProjectScoreModel(ScoreCategory.Carbon, time3, null, null),
                  ),
          )

      val actual =
          store.fetchScores(
              projectId,
              setOf(
                  CohortPhase.Phase0DueDiligence,
                  CohortPhase.Phase1FeasibilityStudy,
                  CohortPhase.Phase3ImplementAndMonitor,
              ),
          )

      assertEquals(expected, actual)
    }

    @Test
    fun `throws exception if no permission to read scores`() {
      val projectId = insertProject(participantId = inserted.participantId)

      every { user.canReadProjectScores(projectId) } returns false

      assertThrows<AccessDeniedException> { store.fetchScores(projectId) }
    }
  }

  @Nested
  inner class UpdateScores {
    @Test
    fun `creates scores that do not exist`() {
      val projectId = insertProject(participantId = inserted.participantId)

      clock.instant = Instant.ofEpochSecond(123)

      store.updateScores(
          projectId,
          CohortPhase.Phase0DueDiligence,
          listOf(
              NewProjectScoreModel(ScoreCategory.Carbon, null, "q1", 1),
              NewProjectScoreModel(ScoreCategory.Legal, null, null, null),
          ),
      )

      val commonRow =
          ProjectScoresRow(
              projectId = projectId,
              phaseId = CohortPhase.Phase0DueDiligence,
              createdBy = user.userId,
              createdTime = clock.instant,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )

      val expected =
          setOf(
              commonRow.copy(
                  scoreCategoryId = ScoreCategory.Carbon,
                  score = 1,
                  qualitative = "q1",
              ),
              commonRow.copy(
                  scoreCategoryId = ScoreCategory.Legal,
                  score = null,
                  qualitative = null,
              ),
          )

      assertSetEquals(expected, projectScoresDao.findAll().toSet())
    }

    @Test
    fun `updates scores that already exist`() {
      val projectId = insertProject(participantId = inserted.participantId)

      insertProjectScore(phase = CohortPhase.Phase0DueDiligence, category = ScoreCategory.Legal)
      insertProjectScore(
          phase = CohortPhase.Phase0DueDiligence,
          category = ScoreCategory.Forestry,
          qualitative = "q",
          score = 1,
      )

      clock.instant = Instant.ofEpochSecond(123)

      store.updateScores(
          projectId,
          CohortPhase.Phase0DueDiligence,
          listOf(
              NewProjectScoreModel(ScoreCategory.Legal, null, "q1", 1),
              NewProjectScoreModel(ScoreCategory.Forestry, null, null, null),
          ),
      )

      val commonRow =
          ProjectScoresRow(
              projectId = projectId,
              phaseId = CohortPhase.Phase0DueDiligence,
              createdBy = user.userId,
              createdTime = Instant.EPOCH,
              modifiedBy = user.userId,
              modifiedTime = clock.instant,
          )

      val expected =
          setOf(
              commonRow.copy(
                  scoreCategoryId = ScoreCategory.Legal,
                  score = 1,
                  qualitative = "q1",
              ),
              commonRow.copy(
                  scoreCategoryId = ScoreCategory.Forestry,
                  score = null,
                  qualitative = null,
              ),
          )

      assertSetEquals(expected, projectScoresDao.findAll().toSet())
    }

    @Test
    fun `throws exception if no permission to update scores`() {
      val projectId = insertProject(participantId = inserted.participantId)

      every { user.canUpdateProjectScores(projectId) } returns false

      assertThrows<AccessDeniedException> {
        store.updateScores(
            projectId,
            CohortPhase.Phase0DueDiligence,
            listOf(NewProjectScoreModel(ScoreCategory.Legal, null, null, 1)),
        )
      }
    }

    @Test
    fun `throws exception if updating scores for a non-participant project`() {
      val projectId = insertProject()

      assertThrows<ProjectNotInCohortException> {
        store.updateScores(
            projectId,
            CohortPhase.Phase0DueDiligence,
            listOf(NewProjectScoreModel(ScoreCategory.Legal, null, null, 1)),
        )
      }
    }

    @Test
    fun `throws exception if updating scores for a different phase than the current one`() {
      val projectId = insertProject(participantId = inserted.participantId)

      assertThrows<ProjectNotInCohortPhaseException> {
        store.updateScores(
            projectId,
            CohortPhase.Phase1FeasibilityStudy,
            listOf(NewProjectScoreModel(ScoreCategory.Legal, null, null, 1)),
        )
      }
    }
  }
}
