package com.terraformation.backend.accelerator.db

import com.terraformation.backend.accelerator.model.ExistingParticipantProjectSpeciesModel
import com.terraformation.backend.accelerator.model.ParticipantProjectSpeciesModel
import com.terraformation.backend.auth.currentUser
import com.terraformation.backend.db.accelerator.ParticipantProjectSpeciesId
import com.terraformation.backend.db.accelerator.tables.references.PARTICIPANT_PROJECT_SPECIES
import com.terraformation.backend.db.default_schema.ProjectId
import jakarta.inject.Named
import org.jooq.Condition
import org.jooq.DSLContext

@Named
class ParticipantProjectSpeciesStore(
    private val dslContext: DSLContext,
) {
  fun fetchOneById(
      participantProjectSpeciesId: ParticipantProjectSpeciesId
  ): ExistingParticipantProjectSpeciesModel {
    return fetch(PARTICIPANT_PROJECT_SPECIES.ID.eq(participantProjectSpeciesId)).firstOrNull()
        ?: throw ParticipantProjectSpeciesNotFoundException(participantProjectSpeciesId)
  }

  fun findAllForProject(projectId: ProjectId): List<ExistingParticipantProjectSpeciesModel> {
    return fetch(PARTICIPANT_PROJECT_SPECIES.PROJECT_ID.eq(projectId))
  }

  private fun fetch(condition: Condition?): List<ExistingParticipantProjectSpeciesModel> {
    val user = currentUser()

    return with(PARTICIPANT_PROJECT_SPECIES) {
      dslContext
          .select(PARTICIPANT_PROJECT_SPECIES.asterisk())
          .from(PARTICIPANT_PROJECT_SPECIES)
          .apply { condition?.let { where(it) } }
          .orderBy(ID)
          .fetch { ParticipantProjectSpeciesModel.of(it) }
          .filter { user.canReadParticipantProjectSpecies(it.id) }
    }
  }
}
