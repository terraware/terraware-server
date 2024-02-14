package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.default_schema.CohortId
import com.terraformation.backend.db.default_schema.CohortPhase
import com.terraformation.backend.db.default_schema.ParticipantId
import com.terraformation.backend.db.default_schema.tables.pojos.CohortsRow
import com.terraformation.backend.db.default_schema.tables.references.COHORTS
import org.jooq.Field
import org.jooq.Record

data class CohortModel<ID : CohortId?, PARTICIPANT_IDS : Set<ParticipantId>?>(
    val id: ID,
    val name: String,
    val participantIds: PARTICIPANT_IDS,
    val phase: CohortPhase
) {
  companion object {
    fun of(
        record: Record,
        participantIdsField: Field<Set<ParticipantId>>? = null
    ): ExistingCohortModel {
      return ExistingCohortModel(
          id = record[COHORTS.ID]!!,
          name = record[COHORTS.NAME]!!,
          participantIds = participantIdsField?.let { record[it] } ?: emptySet(),
          phase = record[COHORTS.PHASE_ID]!!,
      )
    }

    fun create(name: String, phase: CohortPhase): NewCohortModel {
      return NewCohortModel(
          id = null,
          name = name,
          participantIds = null,
          phase = phase,
      )
    }
  }
}

typealias ExistingCohortModel = CohortModel<CohortId, Set<ParticipantId>>

typealias NewCohortModel = CohortModel<Nothing?, Nothing?>

fun CohortsRow.toModel(participantIds: Set<ParticipantId> = emptySet()): ExistingCohortModel {
  return ExistingCohortModel(
      id = id!!,
      name = name!!,
      participantIds = participantIds,
      phase = phaseId!!,
  )
}
