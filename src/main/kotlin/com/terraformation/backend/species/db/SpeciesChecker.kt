package com.terraformation.backend.species.db

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.species.model.ExistingSpeciesModel
import jakarta.inject.Named

@Named
class SpeciesChecker(
    private val gbifStore: GbifStore,
    private val speciesStore: SpeciesStore,
) {
  fun checkAllUncheckedSpecies(organizationId: OrganizationId) {
    speciesStore.fetchUncheckedSpeciesIds(organizationId).forEach { checkSpecies(it) }
  }

  fun checkSpecies(speciesId: SpeciesId) {
    val model = speciesStore.fetchSpeciesById(speciesId)
    if (model.checkedTime == null) {
      createProblems(model)
    }
  }

  /**
   * Rechecks a species after it has been updated if the update modified any values that would cause
   * previous check results to be invalid.
   */
  fun recheckSpecies(before: ExistingSpeciesModel, after: ExistingSpeciesModel) {
    if (before.scientificName != after.scientificName) {
      createProblems(after)
    }
  }

  private fun createProblems(model: ExistingSpeciesModel) {
    val problems = listOfNotNull(gbifStore.checkScientificName(model.scientificName))

    speciesStore.updateProblems(model.id, problems)
  }
}
