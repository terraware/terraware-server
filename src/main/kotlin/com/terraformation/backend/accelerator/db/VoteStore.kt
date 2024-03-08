package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.VoteModel
import com.terraformation.backend.accelerator.model.toModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.daos.ProjectVotesDao
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectVotesRow
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VOTES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class VoteStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val projectVotesDao: ProjectVotesDao,
) {
  fun fetchAllVotesByProject(projectId: ProjectId): List<VoteModel> {
    return fetch(PROJECT_VOTES.PROJECT_ID.eq(projectId))
  }

  fun create(model: VoteModel): VoteModel {
    val now = clock.instant()
    val currentUserId = currentUser().userId

    val row =
        ProjectVotesRow(
            userId = model.userId,
            projectId = model.projectId,
            phaseId = model.phase,
            voteOptionId = model.voteOption,
            conditionalInfo = model.conditionalInfo,
            createdBy = currentUserId,
            createdTime = now,
            modifiedBy = currentUserId,
            modifiedTime = now)

    projectVotesDao.insert(row)
    return row.toModel()
  }

  fun delete(projectId: ProjectId, phase: CohortPhase, userId: UserId) {
    dslContext.transaction { _ ->
      val rowsDeleted =
          dslContext
              .deleteFrom(PROJECT_VOTES)
              .where(
                  listOf(
                      PROJECT_VOTES.PROJECT_ID.eq(projectId),
                      PROJECT_VOTES.PHASE_ID.eq(phase),
                      PROJECT_VOTES.USER_ID.eq(userId),
                  ))
              .execute()

      if (rowsDeleted == 0) {
        throw ProjectVoteNotFoundException(projectId, phase, userId)
      }
    }
  }

  fun updateVoteOption(
      projectId: ProjectId,
      phase: CohortPhase,
      userId: UserId,
      voteOption: VoteOption
  ) {
    val rowsUpdated =
        with(PROJECT_VOTES) {
          dslContext
              .update(PROJECT_VOTES)
              .set(MODIFIED_BY, currentUser().userId)
              .set(MODIFIED_TIME, clock.instant())
              .set(VOTE_OPTION_ID, voteOption)
              .where(
                  listOf(
                      PROJECT_VOTES.PROJECT_ID.eq(projectId),
                      PROJECT_VOTES.PHASE_ID.eq(phase),
                      PROJECT_VOTES.USER_ID.eq(userId),
                  ))
              .execute()
        }

    if (rowsUpdated < 1) {
      throw ProjectVoteNotFoundException(projectId, phase, userId)
    }
  }

  private fun fetch(condition: Condition?): List<VoteModel> {
    return dslContext
        .select(PROJECT_VOTES.asterisk())
        .from(PROJECT_VOTES)
        .apply { condition?.let { where(it) } }
        .orderBy(emptySet())
        .fetch { VoteModel.of(it) }
  }
}
