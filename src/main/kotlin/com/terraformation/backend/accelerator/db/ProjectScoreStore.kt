package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ExistingProjectScoreModel
import com.terraformation.backend.accelerator.model.NewProjectScoreModel
import com.terraformation.backend.accelerator.model.ProjectScoreModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_SCORES
import com.terraformation.backend.db.asNonNullable
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext

@Named
class ProjectScoreStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val projectCohortFetcher: ProjectCohortFetcher,
) {
  /**
   * Returns the per-category scores for a project.
   *
   * @param phases If non-null, only return scores for these phases.
   */
  fun fetchScores(
      projectId: ProjectId,
      phases: Collection<CohortPhase>? = null,
  ): Map<CohortPhase, List<ExistingProjectScoreModel>> {
    requirePermissions { readProjectScores(projectId) }

    return with(PROJECT_SCORES) {
      val conditions =
          listOfNotNull(
              phases?.let { PHASE_ID.`in`(it) },
              PROJECT_ID.eq(projectId),
          )

      dslContext
          .select(PHASE_ID, MODIFIED_TIME, QUALITATIVE, SCORE, SCORE_CATEGORY_ID)
          .from(PROJECT_SCORES)
          .where(conditions)
          .orderBy(PHASE_ID, SCORE_CATEGORY_ID)
          .fetchGroups(PHASE_ID.asNonNullable()) { ProjectScoreModel.of(it) }
    }
  }

  fun updateScores(
      projectId: ProjectId,
      phase: CohortPhase,
      scores: Collection<NewProjectScoreModel>,
  ) {
    requirePermissions { updateProjectScores(projectId) }

    projectCohortFetcher.ensureProjectPhase(projectId, phase)

    val now = clock.instant()
    val userId = currentUser().userId

    dslContext.transaction { _ ->
      scores.forEach { score ->
        with(PROJECT_SCORES) {
          dslContext
              .insertInto(PROJECT_SCORES)
              .set(CREATED_BY, userId)
              .set(CREATED_TIME, now)
              .set(MODIFIED_BY, userId)
              .set(MODIFIED_TIME, now)
              .set(PHASE_ID, phase)
              .set(PROJECT_ID, projectId)
              .set(QUALITATIVE, score.qualitative)
              .set(SCORE, score.score)
              .set(SCORE_CATEGORY_ID, score.category)
              .onConflict()
              .doUpdate()
              .set(MODIFIED_BY, userId)
              .set(MODIFIED_TIME, now)
              .set(QUALITATIVE, score.qualitative)
              .set(SCORE, score.score)
              .execute()
        }
      }
    }
  }
}
