package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.VoteDecisionModel
import com.terraformation.backend.accelerator.model.VoteModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VOTES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VOTE_DECISIONS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import jakarta.inject.Named
import java.time.Instant
import java.time.InstantSource
import org.jooq.DSLContext

@Named
class VoteStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
) {
  fun fetchAllVotes(projectId: ProjectId): List<VoteModel> {
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

  fun fetchAllVoteDecisions(projectId: ProjectId): List<VoteDecisionModel> {
    requirePermissions { readProjectVotes(projectId) }
    return with(PROJECT_VOTE_DECISIONS) {
      dslContext
          .select(PROJECT_VOTE_DECISIONS.asterisk())
          .from(PROJECT_VOTE_DECISIONS)
          .where(PROJECT_ID.eq(projectId))
          .orderBy(PHASE_ID)
          .fetch { VoteDecisionModel.of(it) }
    }
  }

  /**
   * Deletes a voter from the project phase, making them ineligible to cast. This is different from
   * a vote. This is different from removing a vote selection by setting a vote to `null`.
   *
   * Returns the vote decision after the removal of voter.
   */
  fun delete(projectId: ProjectId, phase: CohortPhase, userId: UserId? = null): VoteDecisionModel {
    requirePermissions { updateProjectVotes(projectId) }

    if (getProjectCohortPhase(projectId) != phase) {
      throw ProjectNotInCohortPhaseException(projectId, phase)
    }

    val now = clock.instant()

    val conditions =
        listOfNotNull(
            if (userId != null) PROJECT_VOTES.USER_ID.eq(userId) else null,
            PROJECT_VOTES.PHASE_ID.eq(phase),
            PROJECT_VOTES.PROJECT_ID.eq(projectId))
    val rowsDeleted = dslContext.deleteFrom(PROJECT_VOTES).where(conditions).execute()

    if (rowsDeleted == 0) {
      throw ProjectVoteNotFoundException(projectId, phase, userId)
    }

    return updateProjectVoteDecisions(projectId, phase, now)
  }

  /**
   * Returns the vote decision after the upsert.
   */
  fun upsert(
      projectId: ProjectId,
      phase: CohortPhase,
      userId: UserId,
      voteOption: VoteOption? = null,
      conditionalInfo: String? = null,
  ): VoteDecisionModel {
    requirePermissions { updateProjectVotes(projectId) }

    if (getProjectCohortPhase(projectId) != phase) {
      throw ProjectNotInCohortPhaseException(projectId, phase)
    }

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

    return updateProjectVoteDecisions(projectId, phase, now)
  }

  private fun getProjectCohortPhase(projectId: ProjectId): CohortPhase {
    return dslContext
        .select(COHORTS.PHASE_ID)
        .from(PROJECTS)
        .join(PARTICIPANTS)
        .on(PROJECTS.PARTICIPANT_ID.eq(PARTICIPANTS.ID))
        .join(COHORTS)
        .on(PARTICIPANTS.COHORT_ID.eq(COHORTS.ID))
        .where(PROJECTS.ID.eq(projectId))
        .fetchOne(COHORTS.PHASE_ID) ?: throw ProjectNotInCohortException(projectId)
  }

  private fun updateProjectVoteDecisions(
      projectId: ProjectId,
      phase: CohortPhase,
      now: Instant = clock.instant()
  ): VoteDecisionModel {
    val votes =
        dslContext
            .select(PROJECT_VOTES.VOTE_OPTION_ID)
            .from(PROJECT_VOTES)
            .where(PROJECT_VOTES.PROJECT_ID.eq(projectId))
            .and((PROJECT_VOTES.PHASE_ID.eq(phase)))
            .fetch (PROJECT_VOTES.VOTE_OPTION_ID)

    val decision =
        if (votes.isEmpty()) {
          // If no voter exists (e.g. last vote deleted)
          null
        } else if (votes.any { it == null }) {
          // If one or more voter has not submitted a vote
          null
        } else {
          val counts = votes.groupingBy { it!! }.eachCount()
          val highestCount = counts.maxOf { it.value }
          val majority = counts.filterValues { it == highestCount }.keys

          // Return only if one relative majority. Tie results in null.
          majority.singleOrNull()
        }

    with(PROJECT_VOTES) {
      dslContext
          .insertInto(PROJECT_VOTE_DECISIONS)
          .set(PROJECT_ID, projectId)
          .set(PHASE_ID, phase)
          .set(VOTE_OPTION_ID, decision)
          .set(MODIFIED_TIME, now)
          .onDuplicateKeyUpdate()
          .set(VOTE_OPTION_ID, decision)
          .set(MODIFIED_TIME, now)
          .execute()
    }
    return VoteDecisionModel(projectId, phase, now, decision)
  }
}
