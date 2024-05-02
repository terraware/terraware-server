package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantProjectSpeciesRow
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANT_PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import org.jooq.Record

data class ParticipantProjectSpeciesModel<ID : ParticipantProjectSpeciesId?>(
    val id: ID,
    val feedback: String?,
    val projectId: ProjectId,
    val rationale: String?,
    val speciesId: SpeciesId,
    val submissionStatus: SubmissionStatus?,
) {
  companion object {
    fun of(record: Record): ExistingParticipantProjectSpeciesModel {
      return ExistingParticipantProjectSpeciesModel(
          feedback = record[PARTICIPANT_PROJECT_SPECIES.FEEDBACK],
          id = record[PARTICIPANT_PROJECT_SPECIES.ID]!!,
          projectId = record[PARTICIPANT_PROJECT_SPECIES.PROJECT_ID]!!,
          rationale = record[PARTICIPANT_PROJECT_SPECIES.RATIONALE],
          speciesId = record[PARTICIPANT_PROJECT_SPECIES.SPECIES_ID]!!,
          submissionStatus = record[PARTICIPANT_PROJECT_SPECIES.SUBMISSION_STATUS_ID]!!,
      )
    }
  }
}

typealias ExistingParticipantProjectSpeciesModel =
    ParticipantProjectSpeciesModel<ParticipantProjectSpeciesId>

typealias NewParticipantProjectSpeciesModel = ParticipantProjectSpeciesModel<Nothing?>

fun ParticipantProjectSpeciesRow.toModel(): ExistingParticipantProjectSpeciesModel {
  return ExistingParticipantProjectSpeciesModel(
      feedback = feedback,
      id = id!!,
      projectId = projectId!!,
      rationale = rationale,
      speciesId = speciesId!!,
      submissionStatus = submissionStatusId!!,
  )
}
