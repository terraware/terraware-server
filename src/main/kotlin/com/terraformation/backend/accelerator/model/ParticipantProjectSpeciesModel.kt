package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.pojos.ParticipantProjectSpeciesRow
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANT_PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesNativeCategory
import com.terraformation.backend.db.default_schema.UserId
import java.time.Instant
import org.jooq.Record

data class ParticipantProjectSpeciesModel<ID : ParticipantProjectSpeciesId?>(
    val createdBy: UserId? = null,
    val createdTime: Instant? = null,
    val id: ID,
    val feedback: String? = null,
    val internalComment: String? = null,
    val modifiedBy: UserId? = null,
    val modifiedTime: Instant? = null,
    val speciesNativeCategory: SpeciesNativeCategory? = null,
    val projectId: ProjectId,
    val rationale: String? = null,
    val speciesId: SpeciesId,
    val submissionStatus: SubmissionStatus = SubmissionStatus.NotSubmitted,
) {
  companion object {
    fun of(record: Record): ExistingParticipantProjectSpeciesModel {
      return ExistingParticipantProjectSpeciesModel(
          createdBy = record[PARTICIPANT_PROJECT_SPECIES.CREATED_BY]!!,
          createdTime = record[PARTICIPANT_PROJECT_SPECIES.CREATED_TIME]!!,
          feedback = record[PARTICIPANT_PROJECT_SPECIES.FEEDBACK],
          internalComment = record[PARTICIPANT_PROJECT_SPECIES.INTERNAL_COMMENT],
          id = record[PARTICIPANT_PROJECT_SPECIES.ID]!!,
          modifiedBy = record[PARTICIPANT_PROJECT_SPECIES.MODIFIED_BY]!!,
          modifiedTime = record[PARTICIPANT_PROJECT_SPECIES.MODIFIED_TIME]!!,
          projectId = record[PARTICIPANT_PROJECT_SPECIES.PROJECT_ID]!!,
          rationale = record[PARTICIPANT_PROJECT_SPECIES.RATIONALE],
          speciesId = record[PARTICIPANT_PROJECT_SPECIES.SPECIES_ID]!!,
          speciesNativeCategory = record[PARTICIPANT_PROJECT_SPECIES.SPECIES_NATIVE_CATEGORY_ID],
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
      createdBy = createdBy!!,
      createdTime = createdTime!!,
      feedback = feedback,
      internalComment = internalComment,
      id = id!!,
      modifiedBy = modifiedBy!!,
      modifiedTime = modifiedTime!!,
      projectId = projectId!!,
      rationale = rationale,
      speciesId = speciesId!!,
      speciesNativeCategory = speciesNativeCategoryId,
      submissionStatus = submissionStatusId!!,
  )
}
