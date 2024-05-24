package com.terraformation.backend.accelerator.model

import com.terraformation.backend.customer.model.ExistingProjectModel
import com.terraformation.backend.species.model.ExistingSpeciesModel
import org.jooq.Record

data class SpeciesForParticipantProject(
    val participantProjectSpecies: ExistingParticipantProjectSpeciesModel,
    val project: ExistingProjectModel,
    val species: ExistingSpeciesModel,
) {
  companion object {
    fun of(record: Record): SpeciesForParticipantProject {
      return SpeciesForParticipantProject(
          participantProjectSpecies = ExistingParticipantProjectSpeciesModel.of(record),
          species = ExistingSpeciesModel.of(record),
          project = ExistingProjectModel.of(record),
      )
    }
  }
}
