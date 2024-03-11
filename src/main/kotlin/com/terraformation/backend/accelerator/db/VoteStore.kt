package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.VoteModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.daos.ProjectVotesDao
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VOTES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import jakarta.inject.Named
import java.time.InstantSource
import org.jooq.DSLContext

@Named
class VoteStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val projectVotesDao: ProjectVotesDao,
) {
  fun fetchAllVotesByProject(projectId: ProjectId): List<VoteModel> {
    requirePermissions { readProjectVotes(projectId) }
    return with(PROJECT_VOTES) {
      dslContext
          .select(PROJECT_VOTES.asterisk())
          .from(PROJECT_VOTES)
          .where(PROJECT_ID.eq(projectId))
          .orderBy(PROJECT_ID, PHASE_ID, USER_ID)
          .fetch { VoteModel.of(it) }
    }
  }

  fun delete(projectId: ProjectId, phase: CohortPhase? = null, userId: UserId? = null) {
    requirePermissions { updateProjectVotes(projectId) }
    val conditions =
        listOfNotNull(
            if (phase != null) PROJECT_VOTES.PHASE_ID.eq(phase) else null,
            if (userId != null) PROJECT_VOTES.USER_ID.eq(userId) else null,
            projectId.let { PROJECT_VOTES.PROJECT_ID.eq(projectId) })
    val rowsDeleted = dslContext.deleteFrom(PROJECT_VOTES).where(conditions).execute()

    if (rowsDeleted == 0) {
      throw ProjectVoteNotFoundException(projectId, phase, userId)
    }
  }

  fun upsert(
      projectId: ProjectId,
      phase: CohortPhase,
      userId: UserId,
      voteOption: VoteOption? = null,
      conditionalInfo: String? = null,
  ) {
    requirePermissions { updateProjectVotes(projectId) }
    val now = clock.instant()
    val currentUserId = currentUser().userId

    with(PROJECT_VOTES) {
      dslContext
          .insertInto(PROJECT_VOTES)
          .set(USER_ID, userId)
          .set(PROJECT_ID, projectId)
          .set(PHASE_ID, phase)
          .set(CREATED_BY, currentUserId)
          .set(CREATED_TIME, now)
          .set(MODIFIED_BY, currentUserId)
          .set(MODIFIED_TIME, now)
          .set(VOTE_OPTION_ID, voteOption)
          .set(CONDITIONAL_INFO, conditionalInfo)
          .onDuplicateKeyUpdate()
          .set(MODIFIED_BY, currentUserId)
          .set(MODIFIED_TIME, now)
          .set(VOTE_OPTION_ID, voteOption)
          .set(CONDITIONAL_INFO, conditionalInfo)
          .execute()
    }
  }
}
