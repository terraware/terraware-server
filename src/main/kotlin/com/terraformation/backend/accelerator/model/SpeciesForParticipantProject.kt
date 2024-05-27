package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANT_PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import org.jooq.Record

data class SpeciesForParticipantProject(
    val participantProjectSpeciesId: ParticipantProjectSpeciesId,
    val participantProjectSpeciesRationale: String?,
    val participantProjectSpeciesSubmissionStatus: SubmissionStatus,
    val participantProjectSpeciesNativeCategory: SpeciesNativeCategory?,
    val projectId: ProjectId,
    val speciesId: SpeciesId,
    val speciesCommonName: String?,
    val speciesScientificName: String,
) {
  companion object {
    fun of(record: Record): SpeciesForParticipantProject {
      return SpeciesForParticipantProject(
          participantProjectSpeciesId = record[PARTICIPANT_PROJECT_SPECIES.ID]!!,
          participantProjectSpeciesRationale = record[PARTICIPANT_PROJECT_SPECIES.RATIONALE],
          participantProjectSpeciesSubmissionStatus =
              record[PARTICIPANT_PROJECT_SPECIES.SUBMISSION_STATUS_ID]!!,
          participantProjectSpeciesNativeCategory =
              record[PARTICIPANT_PROJECT_SPECIES.SPECIES_NATIVE_CATEGORY_ID]!!,
          projectId = record[PROJECTS.ID]!!,
          speciesId = record[SPECIES.ID]!!,
          speciesCommonName = record[SPECIES.COMMON_NAME],
          speciesScientificName = record[SPECIES.SCIENTIFIC_NAME]!!,
      )
    }
  }
}
