package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectVoteDecisionsRow
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VOTE_DECISIONS
import com.terraformation.backend.db.default_schema.ProjectId
import java.time.Instant
import org.jooq.Record

data class VoteDecisionModel(
    val phase: CohortPhase,
    val modifiedTime: Instant,
    val decision: VoteOption? = null,
) {
  companion object {
    fun of(
        record: Record,
    ): VoteDecisionModel {
      return VoteDecisionModel(
          phase = record[PROJECT_VOTE_DECISIONS.PHASE_ID]!!,
          modifiedTime = record[PROJECT_VOTE_DECISIONS.MODIFIED_TIME]!!,
          decision = record[PROJECT_VOTE_DECISIONS.VOTE_OPTION_ID],
      )
    }
  }
}