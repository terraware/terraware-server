package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VOTES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId
import com.terraformation.backend.db.default_schema.tables.references.USERS
import org.jooq.Record

data class VoteModel(
    val conditionalInfo: String? = null,
    val email: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val phase: CohortPhase,
    val userId: UserId,
    val voteOption: VoteOption? = null,
) {
  companion object {
    fun of(
        record: Record,
    ): VoteModel {
      return VoteModel(
          conditionalInfo = record[PROJECT_VOTES.CONDITIONAL_INFO],
          email = record[USERS.EMAIL]!!,
          firstName = record[USERS.FIRST_NAME],
          lastName = record[USERS.LAST_NAME],
          phase = record[PROJECT_VOTES.PHASE_ID]!!,
          userId = record[PROJECT_VOTES.USER_ID]!!,
          voteOption = record[PROJECT_VOTES.VOTE_OPTION_ID],
      )
    }
  }
}
