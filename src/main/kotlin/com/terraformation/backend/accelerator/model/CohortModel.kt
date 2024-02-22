package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.CohortId
import com.terraformation.backend.db.accelerator.CohortPhase
import com.terraformation.backend.db.accelerator.ParticipantId
import com.terraformation.backend.db.accelerator.tables.pojos.CohortsRow
import com.terraformation.backend.db.accelerator.tables.references.COHORTS
import org.jooq.Field
import org.jooq.Record

data class CohortModel<ID : CohortId?>(
    val id: ID,
    val name: String,
    val participantIds: Set<ParticipantId>,
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
          participantIds = emptySet(),
          phase = phase,
      )
    }
  }
}

typealias ExistingCohortModel = CohortModel<CohortId>

typealias NewCohortModel = CohortModel<Nothing?>

fun CohortsRow.toModel(participantIds: Set<ParticipantId> = emptySet()): ExistingCohortModel {
  return ExistingCohortModel(
      id = id!!,
      name = name!!,
      participantIds = participantIds,
      phase = phaseId!!,
  )
}
