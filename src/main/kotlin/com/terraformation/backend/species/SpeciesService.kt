package com.terraformation.backend.species

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.SpeciesInUseException
import com.terraformation.backend.db.default_schema.SpeciesId
import com.terraformation.backend.species.db.SpeciesChecker
import com.terraformation.backend.species.db.SpeciesStore
import com.terraformation.backend.species.event.SpeciesEditedEvent
import com.terraformation.backend.species.model.ExistingSpeciesModel
import com.terraformation.backend.species.model.NewSpeciesModel
import jakarta.inject.Named
import org.jooq.DSLContext
import org.springframework.context.ApplicationEventPublisher

@Named
class SpeciesService(
    private val dslContext: DSLContext,
    private val eventPublisher: ApplicationEventPublisher,
    private val speciesChecker: SpeciesChecker,
    private val speciesStore: SpeciesStore,
) {
  /** Creates a new species and checks it for potential problems. */
  fun createSpecies(model: NewSpeciesModel): SpeciesId {
    return dslContext.transactionResult { _ ->
      val speciesId = speciesStore.createSpecies(model)
      speciesChecker.checkSpecies(speciesId)
      speciesId
    }
  }

  /** Updates an existing species and checks it for newly-introduced problems. */
  fun updateSpecies(model: ExistingSpeciesModel): ExistingSpeciesModel {
    return dslContext.transactionResult { _ ->
      val existingRow = speciesStore.fetchSpeciesById(model.id)

      val updatedRow = speciesStore.updateSpecies(model)
      speciesChecker.recheckSpecies(existingRow, updatedRow)

      eventPublisher.publishEvent(SpeciesEditedEvent(species = updatedRow))

      updatedRow
    }
  }

  /** Deletes a species if it is not used in the organization. throws [SpeciesInUseException] */
  fun deleteSpecies(speciesId: SpeciesId) {
    requirePermissions { deleteSpecies(speciesId) }

    if (speciesStore.isInUse(speciesId)) {
      throw SpeciesInUseException(speciesId)
    }

    speciesStore.deleteSpecies(speciesId)
  }
}
