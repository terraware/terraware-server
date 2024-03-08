package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.pojos.ProjectVotesRow
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.UserId

data class VoteModel(
    val projectId: ProjectId,
    val phase: CohortPhase,
    val userId: UserId,
    val voteOption: VoteOption?,
    val conditionalInfo: String?
) {
  companion object {
    fun create(
        projectId: ProjectId,
        phase: CohortPhase,
        userId: UserId,
        voteOption: VoteOption?,
        conditionalInfo: String?
    ): VoteModel {
      return VoteModel(
          projectId = projectId,
          phase = phase,
          userId = userId,
          voteOption = voteOption,
          conditionalInfo = conditionalInfo)
    }
  }
}

fun ProjectVotesRow.toModel(): VoteModel {
  return VoteModel(
      userId = userId!!,
      projectId = projectId!!,
      phase = phaseId!!,
      voteOption = voteOptionId,
      conditionalInfo = conditionalInfo,
  )
}
