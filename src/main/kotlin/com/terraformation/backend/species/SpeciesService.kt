package com.terraformation.backend.species

import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.db.default_schema.tables.pojos.SpeciesRow
import com.terraformation.backend.species.db.SpeciesChecker
import com.terraformation.backend.species.db.SpeciesStore
import javax.annotation.ManagedBean
import org.jooq.DSLContext

@ManagedBean
class SpeciesService(
    private val dslContext: DSLContext,
    private val speciesChecker: SpeciesChecker,
    private val speciesStore: SpeciesStore,
) {
  /** Creates a new species and checks it for potential problems. */
  fun createSpecies(row: SpeciesRow): SpeciesId {
    return dslContext.transactionResult { _ ->
      val speciesId = speciesStore.createSpecies(row)
      speciesChecker.checkSpecies(speciesId)
      speciesId
    }
  }

  /** Updates an existing species and checks it for newly-introduced problems. */
  fun updateSpecies(row: SpeciesRow): SpeciesRow {
    return dslContext.transactionResult { _ ->
      val speciesId = row.id ?: throw IllegalArgumentException("ID must be non-null")
      val existingRow = speciesStore.fetchSpeciesById(speciesId)

      val updatedRow = speciesStore.updateSpecies(row)
      speciesChecker.recheckSpecies(existingRow, updatedRow)

      updatedRow
    }
  }
}
