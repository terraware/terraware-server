package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.VoteDecisionModel
import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.accelerator.tables.daos.ProjectVotesDao
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VOTE_DECISIONS
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class VoteDecisionStore(
  private val dslContext: DSLContext,
) {
  fun fetchProjectVoteDecisions(projectId: ProjectId) : List<VoteDecisionModel> {
    requirePermissions { readProjectVotes(projectId) }
    return dslContext
        .select(PROJECT_VOTE_DECISIONS.asterisk())
        .from(PROJECT_VOTE_DECISIONS)
        .where(PROJECT_VOTE_DECISIONS.PROJECT_ID.eq(projectId))
        .orderBy(PROJECT_VOTE_DECISIONS.PHASE_ID)
        .fetch { VoteDecisionModel.of(it) }
  }
}