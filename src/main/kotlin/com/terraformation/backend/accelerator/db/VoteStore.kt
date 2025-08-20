package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.VoteDecisionModel
import com.terraformation.backend.accelerator.model.VoteModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.references.DEFAULT_VOTERS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VOTES
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VOTE_DECISIONS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.USERS
import jakarta.inject.Named
import java.time.Instant
import java.time.InstantSource
import org.jooq.DSLContext
import org.jooq.impl.DSL

@Named
class VoteStore(
    private val clock: InstantSource,
    private val dslContext: DSLContext,
    private val projectCohortFetcher: ProjectCohortFetcher,
) {
  fun fetchAllVotes(projectId: ProjectId, phase: CohortPhase? = null): List<VoteModel> {
    requirePermissions { readProjectVotes(projectId) }
    return with(PROJECT_VOTES) {
      dslContext
          .select(asterisk(), USERS.EMAIL, USERS.FIRST_NAME, USERS.LAST_NAME)
          .from(this)
          .join(USERS)
          .on(USERS.ID.eq(USER_ID))
          .where(PROJECT_ID.eq(projectId))
          .and(phase?.let { PHASE_ID.eq(it) })
          .orderBy(PHASE_ID, USER_ID)
          .fetch { VoteModel.of(it) }
    }
  }

  fun fetchAllVoteDecisions(
      projectId: ProjectId,
      phase: CohortPhase? = null,
  ): List<VoteDecisionModel> {
    requirePermissions { readProjectVotes(projectId) }
    return with(PROJECT_VOTE_DECISIONS) {
      dslContext
          .selectFrom(PROJECT_VOTE_DECISIONS)
          .where(PROJECT_ID.eq(projectId))
          .and(phase?.let { PHASE_ID.eq(it) })
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
  fun delete(projectId: ProjectId, phase: CohortPhase, userId: UserId? = null) {
    requirePermissions { updateProjectVotes(projectId) }

    projectCohortFetcher.ensureProjectPhase(projectId, phase)

    val now = clock.instant()

    val conditions =
        listOfNotNull(
            if (userId != null) PROJECT_VOTES.USER_ID.eq(userId) else null,
            PROJECT_VOTES.PHASE_ID.eq(phase),
            PROJECT_VOTES.PROJECT_ID.eq(projectId),
        )
    val rowsDeleted = dslContext.deleteFrom(PROJECT_VOTES).where(conditions).execute()

    if (rowsDeleted == 0) {
      throw ProjectVoteNotFoundException(projectId, phase, userId)
    }

    updateProjectVoteDecisions(projectId, phase, now)
  }

  /** Returns the vote decision after the upsert. */
  fun upsert(
      projectId: ProjectId,
      phase: CohortPhase,
      userId: UserId,
      voteOption: VoteOption? = null,
      conditionalInfo: String? = null,
  ) {
    requirePermissions { updateProjectVotes(projectId) }

    projectCohortFetcher.ensureProjectPhase(projectId, phase)

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

    updateProjectVoteDecisions(projectId, phase, now)
  }

  /**
   * Assigns the correct set of voters to a project for its cohort's current phase, if any.
   *
   * The initial implementation assigns all the default voters.
   */
  fun assignVoters(projectId: ProjectId) {
    requirePermissions { updateProjectVotes(projectId) }

    val now = clock.instant()
    val currentUserId = currentUser().userId
    val phase = projectCohortFetcher.getProjectPhase(projectId) ?: return

    dslContext.transaction { _ ->
      with(PROJECT_VOTES) {
        dslContext
            .insertInto(
                this,
                USER_ID,
                PROJECT_ID,
                PHASE_ID,
                CREATED_BY,
                CREATED_TIME,
                MODIFIED_BY,
                MODIFIED_TIME,
            )
            .select(
                DSL.select(
                        DEFAULT_VOTERS.USER_ID,
                        DSL.value(projectId),
                        DSL.value(phase),
                        DSL.value(currentUserId),
                        DSL.value(now),
                        DSL.value(currentUserId),
                        DSL.value(now),
                    )
                    .from(DEFAULT_VOTERS)
            )
            .onConflict()
            .doNothing()
            .execute()
      }

      updateProjectVoteDecisions(projectId, phase, now)
    }
  }

  private fun updateProjectVoteDecisions(
      projectId: ProjectId,
      phase: CohortPhase,
      now: Instant = clock.instant(),
  ) {
    val votes =
        dslContext
            .select(PROJECT_VOTES.VOTE_OPTION_ID)
            .from(PROJECT_VOTES)
            .where(PROJECT_VOTES.PROJECT_ID.eq(projectId))
            .and((PROJECT_VOTES.PHASE_ID.eq(phase)))
            .fetch(PROJECT_VOTES.VOTE_OPTION_ID)

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
  }
}
