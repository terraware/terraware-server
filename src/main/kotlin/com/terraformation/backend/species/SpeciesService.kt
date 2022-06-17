package com.terraformation.backend.species

import com.terraformation.backend.db.OrganizationId
import com.terraformation.backend.db.SpeciesId
import com.terraformation.backend.db.tables.pojos.SpeciesRow
import com.terraformation.backend.species.db.SpeciesStore
import javax.annotation.ManagedBean

@ManagedBean
class SpeciesService(
    private val speciesStore: SpeciesStore,
) {
  /** Returns an existing species with a scientific name or creates it if it doesn't exist. */
  fun getOrCreateSpecies(organizationId: OrganizationId, scientificName: String): SpeciesId {
    return speciesStore.fetchSpeciesIdByName(organizationId, scientificName)
        ?: createSpecies(
            SpeciesRow(organizationId = organizationId, scientificName = scientificName))
  }

  /** Creates a new species and checks it for potential problems. */
  fun createSpecies(row: SpeciesRow): SpeciesId {
    return speciesStore.createSpecies(row)
  }

  /** Updates an existing species and checks it for newly-introduced problems. */
  fun updateSpecies(row: SpeciesRow) {
    speciesStore.updateSpecies(row)
  }
}
