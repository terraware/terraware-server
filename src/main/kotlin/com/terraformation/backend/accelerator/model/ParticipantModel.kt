package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.default_schema.ParticipantId
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.tables.pojos.ParticipantsRow
import com.terraformation.backend.db.default_schema.tables.references.PARTICIPANTS
import org.jooq.Field
import org.jooq.Record

data class ParticipantModel<ID : ParticipantId?>(
    val id: ID,
    val name: String,
    val projectIds: List<ProjectId>,
) {
  companion object {
    fun of(record: Record, projectIdsField: Field<List<ProjectId>>): ExistingParticipantModel {
      return ExistingParticipantModel(
          id = record[PARTICIPANTS.ID]!!,
          name = record[PARTICIPANTS.NAME]!!,
          projectIds = record[projectIdsField],
      )
    }

    fun create(name: String): NewParticipantModel {
      return NewParticipantModel(
          id = null,
          name = name,
          projectIds = emptyList(),
      )
    }
  }
}

typealias ExistingParticipantModel = ParticipantModel<ParticipantId>

typealias NewParticipantModel = ParticipantModel<Nothing?>

fun ParticipantsRow.toModel(projectIds: List<ProjectId> = emptyList()): ExistingParticipantModel {
  return ExistingParticipantModel(
      id = id!!,
      name = name!!,
      projectIds = projectIds,
  )
}
