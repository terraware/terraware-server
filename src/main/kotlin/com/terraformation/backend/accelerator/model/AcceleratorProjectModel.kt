package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.VoteOption
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANTS
import com.terraformation.backend.db.accelerator.tables.references.PROJECT_VOTE_DECISIONS
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import org.jooq.Record

data class AcceleratorProjectModel(
    val cohortId: CohortId,
    val cohortName: String,
    val participantId: ParticipantId,
    val participantName: String,
    val phase: CohortPhase,
    val phaseName: String = phase.getDisplayName(null),
    val projectId: ProjectId,
    val projectName: String,
    val voteDecision: VoteOption?
) {
  companion object {
    fun of(
        record: Record,
    ): AcceleratorProjectModel {
      return AcceleratorProjectModel(
          cohortId = record[COHORTS.ID]!!,
          cohortName = record[COHORTS.NAME]!!,
          participantId = record[PARTICIPANTS.ID]!!,
          participantName = record[PARTICIPANTS.NAME]!!,
          phase = record[COHORTS.PHASE_ID]!!,
          projectId = record[PROJECTS.ID]!!,
          projectName = record[PROJECTS.NAME]!!,
          voteDecision = record[PROJECT_VOTE_DECISIONS.VOTE_OPTION_ID],
      )
    }
  }
}
