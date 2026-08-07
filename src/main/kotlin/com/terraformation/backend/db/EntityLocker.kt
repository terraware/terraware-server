package com.terraformation.backend.db

import com.terraformation.backend.customer.model.requirePermissions
import com.terraformation.backend.db.tracking.ObservationId
import com.terraformation.backend.db.tracking.PlantingSiteId
import com.terraformation.backend.db.tracking.tables.references.OBSERVATIONS
import com.terraformation.backend.db.tracking.tables.references.PLANTING_SITES
import com.terraformation.backend.tracking.db.ObservationNotFoundException
import com.terraformation.backend.tracking.db.ObservationStore
import com.terraformation.backend.tracking.db.PlantingSiteNotFoundException
import com.terraformation.backend.tracking.model.ExistingObservationModel
import com.terraformation.backend.tracking.model.ObservationModel
import jakarta.inject.Named
import org.jooq.DSLContext

@Named
class EntityLocker(
    private val dslContext: DSLContext,
) {
  /**
   * Locks an observation and calls a function. Starts a database transaction; the function is
   * called with the transaction open, such that the lock is held while the function runs.
   */
  fun <T> withLockedObservation(
      observationId: ObservationId,
      func: (ExistingObservationModel) -> T,
  ): T {
    requirePermissions { updateObservation(observationId) }

    return dslContext.transactionResult { _ ->
      val model =
          dslContext
              .select(OBSERVATIONS.asterisk(), ObservationStore.requestedSubstratumIdsField)
              .from(OBSERVATIONS)
              .where(OBSERVATIONS.ID.eq(observationId))
              .forUpdate()
              .of(OBSERVATIONS)
              .fetchOne { ObservationModel.of(it, ObservationStore.requestedSubstratumIdsField) }
              ?: throw ObservationNotFoundException(observationId)

      func(model)
    }
  }

  /**
   * Acquires a row lock on a planting site and executes a function in a transaction with the lock
   * held.
   */
  fun <T> withLockedPlantingSite(plantingSiteId: PlantingSiteId, func: () -> T): T {
    requirePermissions { updatePlantingSite(plantingSiteId) }

    return dslContext.transactionResult { _ ->
      val rowsLocked =
          dslContext
              .selectOne()
              .from(PLANTING_SITES)
              .where(PLANTING_SITES.ID.eq(plantingSiteId))
              .forUpdate()
              .execute()

      if (rowsLocked != 1) {
        throw PlantingSiteNotFoundException(plantingSiteId)
      }

      func()
    }
  }
}
