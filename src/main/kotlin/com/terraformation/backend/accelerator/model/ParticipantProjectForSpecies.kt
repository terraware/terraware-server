package com.terraformation.backend.accelerator.model

import com.terraformation.backend.db.accelerator.DeliverableId
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.SubmissionStatus
import com.terraformation.backend.db.accelerator.tables.references.DELIVERABLES
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANT_PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.ProjectId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.SpeciesNativeCategory
import com.terraformation.backend.db.default_schema.tables.references.PROJECTS
import com.terraformation.backend.db.default_schema.tables.references.SPECIES
import org.jooq.Record
import org.jooq.impl.DSL

data class ParticipantProjectsForSpecies(
    // This deliverable ID is associated to the active or most recent cohort module, if available
    val deliverableId: DeliverableId? = null,
    val participantProjectSpeciesId: ParticipantProjectSpeciesId,
    val participantProjectSpeciesSubmissionStatus: SubmissionStatus,
    val participantProjectSpeciesNativeCategory: SpeciesNativeCategory?,
    val projectId: ProjectId,
    val projectName: String,
    val speciesId: SpeciesId,
) {
  companion object {
    fun of(record: Record): ParticipantProjectsForSpecies {
      return ParticipantProjectsForSpecies(
          deliverableId = record[DSL.max(DELIVERABLES.ID)],
          participantProjectSpeciesId = record[PARTICIPANT_PROJECT_SPECIES.ID]!!,
          projectId = record[PROJECTS.ID]!!,
          projectName = record[PROJECTS.NAME]!!,
          participantProjectSpeciesSubmissionStatus =
              record[PARTICIPANT_PROJECT_SPECIES.SUBMISSION_STATUS_ID]!!,
          participantProjectSpeciesNativeCategory =
              record[PARTICIPANT_PROJECT_SPECIES.SPECIES_NATIVE_CATEGORY_ID],
          speciesId = record[SPECIES.ID]!!,
      )
    }
  }
}
