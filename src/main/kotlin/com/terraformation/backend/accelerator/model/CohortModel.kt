package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.default_schema.CohortId
import com.terraformation.backend.db.default_schema.CohortPhase
import com.terraformation.backend.db.default_schema.ParticipantId
import com.terraformation.backend.db.default_schema.tables.pojos.CohortsRow
import com.terraformation.backend.db.default_schema.tables.references.COHORTS
import org.jooq.Field
import org.jooq.Record

data class CohortModel<ID : CohortId?>(
    val id: ID,
    val name: String,
    val participantIds: List<ParticipantId>,
    val phase: CohortPhase
) {
  companion object {
    fun of(record: Record, participantIdsField: Field<List<ParticipantId>>): ExistingCohortModel {
      return ExistingCohortModel(
          id = record[COHORTS.ID]!!,
          name = record[COHORTS.NAME]!!,
          participantIds = record[participantIdsField],
          phase = record[COHORTS.PHASE_ID]!!
      )
    }

    fun create(name: String, phase: CohortPhase): NewCohortModel {
      return NewCohortModel(
          id = null,
          name = name,
          participantIds = emptyList(),
          phase = phase
      )
    }
  }
}

typealias ExistingCohortModel = CohortModel<CohortId>

typealias NewCohortModel = CohortModel<Nothing?>

fun CohortsRow.toModel(participantIds: List<ParticipantId> = emptyList()): ExistingCohortModel {
  return ExistingCohortModel(
      id = id!!,
      name = name!!,
      participantIds = participantIds,
      phase = phaseId!!
  )
}
