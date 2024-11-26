package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ProjectOverallScoreModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_OVERALL_SCORES
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext

@Named
class ProjectOverallScoreStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  /** Returns the overall score for a project. If no entry is found, return an empty score model. */
  fun fetch(
      projectId: ProjectId,
  ): ProjectOverallScoreModel {
    requirePermissions { readProjectScores(projectId) }

    return with(PROJECT_OVERALL_SCORES) {
      dslContext.selectFrom(this).where(PROJECT_ID.eq(projectId)).fetchOne {
        ProjectOverallScoreModel.of(it)
      } ?: ProjectOverallScoreModel(projectId = projectId)
    }
  }

  fun update(
      projectId: ProjectId,
      updateFunc: (ProjectOverallScoreModel) -> ProjectOverallScoreModel,
  ) {
    requirePermissions { updateProjectScores(projectId) }

    val now = clock.instant()
    val userId = currentUser().userId

    val existing = fetch(projectId)
    val updated = updateFunc(existing)

    with(PROJECT_OVERALL_SCORES) {
      dslContext
          .insertInto(this)
          .set(CREATED_BY, userId)
          .set(CREATED_TIME, now)
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, now)
          .set(DETAILS_URL, updated.detailsUrl)
          .set(OVERALL_SCORE, updated.overallScore)
          .set(PROJECT_ID, projectId)
          .set(SUMMARY, updated.summary)
          .onConflict()
          .doUpdate()
          .set(MODIFIED_BY, userId)
          .set(MODIFIED_TIME, now)
          .set(DETAILS_URL, updated.detailsUrl)
          .set(OVERALL_SCORE, updated.overallScore)
          .set(SUMMARY, updated.summary)
          .execute()
    }
  }
}
