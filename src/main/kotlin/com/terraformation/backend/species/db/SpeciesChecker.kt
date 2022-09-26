package com.terraformation.backend.species.db

import com.terraformation.backend.db.default_schema.OrganizationId
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import javax.annotation.ManagedBean

@ManagedBean
class SpeciesChecker(
    private val gbifStore: GbifStore,
    private val speciesStore: SpeciesStore,
) {
  fun checkAllUncheckedSpecies(organizationId: OrganizationId) {
    speciesStore.fetchUncheckedSpeciesIds(organizationId).forEach { checkSpecies(it) }
  }

  fun checkSpecies(speciesId: SpeciesId) {
    val speciesRow = speciesStore.fetchSpeciesById(speciesId)
    if (speciesRow.checkedTime == null) {
      createProblems(speciesId, speciesRow)
    }
  }

  /**
   * Rechecks a species after it has been updated if the update modified any values that would cause
   * previous check results to be invalid.
   */
  fun recheckSpecies(before: SpeciesRow, after: SpeciesRow) {
    val speciesId = after.id ?: throw IllegalArgumentException("Species ID must be non-null")

    if (before.scientificName != after.scientificName) {
      createProblems(speciesId, after)
    }
  }

  private fun createProblems(speciesId: SpeciesId, speciesRow: SpeciesRow) {
    val problems =
        listOfNotNull(
            speciesRow.scientificName?.let { gbifStore.checkScientificName(it) },
        )

    speciesStore.updateProblems(speciesId, problems)
  }
}
